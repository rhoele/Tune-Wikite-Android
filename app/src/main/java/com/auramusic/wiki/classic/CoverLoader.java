package com.auramusic.wiki.classic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面 / 头像加载器（对应基准 F9 域名白名单 + 内存缓存 + 代际安全）。
 *
 * <p>三级加载链路，逐级回退：
 * <ol>
 *   <li><b>内存 LRU</b>：约可用堆的 1/8，命中即同步上屏；</li>
 *   <li><b>磁盘 LRU</b>：{@code cacheDir/cover-cache}，缓存<b>原始字节</b>，
 *       因此换页 / 重启后依然免下载，且可按目标尺寸重新采样解码；</li>
 *   <li><b>网络</b>：带 CDN 缩放参数与格式协商，尽量拿到体积更小的编码。</li>
 * </ol>
 *
 * <p>性能策略：
 * <ul>
 *   <li><b>懒加载</b>：只由列表绑定驱动，配合 {@link #preload} 预热当前页；</li>
 *   <li><b>CDN 尺寸参数</b>：网易云 CDN 支持 {@code ?param=WyH}，直接让服务端返回缩略图，
 *       移动网络下首屏体积可下降一个数量级；</li>
 *   <li><b>格式协商</b>：{@code Accept} 优先声明 WebP（Android 12+ 追加 AVIF），
 *       CDN 命中时返回体积更小的编码；解码仍交给 {@link BitmapFactory}。</li>
 * </ul>
 *
 * <p>安全约束：仅允许网易云 CDN（{@code .music.126.net}）与聚合接口自有域名的图片，
 * 其余地址一律拒绝加载，避免任意 URL 拉取。
 * 生命周期约束：回调前校验 ImageView 的 tag 代际，防止复用控件错位。
 */
public final class CoverLoader {

    private static final String TAG = "CoverLoader";

    /** 图片域名白名单后缀 */
    private static final String[] ALLOWED_HOST_SUFFIX = {
            ".music.126.net",
            "music.126.net",
            ".126.net",
            "ne.cdgz.top"
    };

    /** 网易云 CDN 缩略图参数可安全附加的域名后缀 */
    private static final String[] CDN_RESIZE_SUFFIX = {
            ".music.126.net",
            "music.126.net",
            ".126.net"
    };

    private static final int MAX_BITMAP_BYTES = 4 * 1024 * 1024;
    /** 磁盘缓存目录名与容量上限 */
    private static final String DISK_DIR_NAME = "cover-cache";
    private static final long MAX_DISK_BYTES = 32L * 1024 * 1024;

    /** Android 12 起 {@link BitmapFactory} 支持 AVIF，低版本声明了也解不出来，故分级协商 */
    private static final String ACCEPT_IMAGE_WEBP =
            "image/webp,image/apng,image/*;q=0.8,*/*;q=0.5";
    private static final String ACCEPT_IMAGE_AVIF =
            "image/avif,image/webp,image/apng,image/*;q=0.8,*/*;q=0.5";

    private static CoverLoader instance;
    private static Context appContext;

    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 正在解码的图片：同一个 key 只下载 / 解码一次，等待中的控件在完成后一起上屏。
     *
     * <p>换页时「预加载」和「列表绑定」几乎同时请求同一批封面，没有合并的话
     * 每张图会被读盘 + 解码两遍，既慢又抢线程。
     */
    private final Map<String, Inflight> inflight = new HashMap<>();

    private static final class Inflight {
        final List<WeakReference<ImageView>> views = new java.util.ArrayList<>(2);
        final List<Callback> callbacks = new java.util.ArrayList<>(2);
    }

    private volatile File diskDir;

    private CoverLoader() {
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSizeKb = maxMemoryKb / 8;
        memoryCache = new LruCache<String, Bitmap>(cacheSizeKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        executor = Executors.newFixedThreadPool(4);
    }

    /**
     * 注入应用上下文以启用磁盘缓存。
     *
     * <p>由 {@link WikiApp#onCreate} 调用；未注入时自动退化为「内存 + 网络」两级缓存，
     * 功能不受影响。
     */
    public static void init(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
    }

    public static synchronized CoverLoader get() {
        if (instance == null) {
            instance = new CoverLoader();
        }
        return instance;
    }

    /** 域名白名单校验 */
    public static boolean isAllowed(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return false;
        }
        for (String suffix : ALLOWED_HOST_SUFFIX) {
            if (lower.contains(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 封面读取结果回调（需求 4）：用于统计「本次列表是否存在可成功读取的封面」，
     * 以决定是否需要全局隐藏卡片图标。回调始终在主线程执行。
     */
    public interface Callback {
        void onResult(boolean success);
    }

    /**
     * 异步加载图片到 ImageView。
     *
     * @param view   目标控件；加载期间会以 URL 作为 tag 标记代际
     * @param url    图片地址
     * @param target 期望的解码边长（px），用于降采样节省内存
     */
    public void load(final ImageView view, final String url, final int target) {
        load(view, url, target, null);
    }

    /**
     * 异步加载图片到 ImageView，并回报读取是否成功。
     *
     * @param callback 读取结果回调，可为 null；无论命中缓存、下载失败还是域名被拒都会回调一次
     */
    public void load(final ImageView view, final String url, final int target,
                     @Nullable final Callback callback) {
        if (view == null) {
            return;
        }
        if (!isAllowed(url)) {
            view.setTag(null);
            view.setImageDrawable(null);
            dispatch(callback, false);
            return;
        }

        final String memKey = memoryKey(url, target);
        // tag 用原始 URL：换页复用时只需比对 URL 即可判断结果是否已过期
        final boolean sameUrl = url.equals(view.getTag());
        view.setTag(url);

        Bitmap cached = memoryCache.get(memKey);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            dispatch(callback, true);
            return;
        }
        // 仅当控件上还留着「别的」位图时才清掉：占位图（资源图）保持不动，
        // 否则每次绑定都会先空白一帧，再等解码上屏，观感就是「封面加载很慢」。
        if (!sameUrl && view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            view.setImageDrawable(null);
        }

        Inflight job;
        boolean lead = false;
        synchronized (inflight) {
            job = inflight.get(memKey);
            if (job == null) {
                job = new Inflight();
                inflight.put(memKey, job);
                lead = true;
            }
            job.views.add(new WeakReference<>(view));
            if (callback != null) {
                job.callbacks.add(callback);
            }
        }
        if (lead) {
            final Inflight task = job;
            executor.execute(() -> decodeInto(memKey, url, target, task));
        }
    }

    /**
     * 预热封面缓存（首屏 / 翻页完成后调用）。
     *
     * <p>提前把本页封面拉进磁盘并解码进内存，滚动到该条目时 {@link #load} 直接命中缓存；
     * 与 {@link #load} 共用同一份在途任务，不会重复读盘 / 解码。
     */
    public void preload(@Nullable final List<String> urls, final int target) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        for (final String url : urls) {
            if (!isAllowed(url)) {
                continue;
            }
            final String memKey = memoryKey(url, target);
            Bitmap cached = memoryCache.get(memKey);
            if (cached != null && !cached.isRecycled()) {
                continue;
            }
            Inflight job;
            boolean lead = false;
            synchronized (inflight) {
                job = inflight.get(memKey);
                if (job == null) {
                    job = new Inflight();
                    inflight.put(memKey, job);
                    lead = true;
                }
            }
            if (lead) {
                final Inflight task = job;
                executor.execute(() -> decodeInto(memKey, url, target, task));
            }
        }
    }

    /** 后台解码一次，完成后在主线程把结果分发给所有等待者。 */
    private void decodeInto(final String memKey, final String url, final int target,
                            final Inflight task) {
        Bitmap bitmap = null;
        try {
            bitmap = loadBitmap(url, target);
        } catch (Exception | OutOfMemoryError e) {
            // 下载/解码异常与内存不足都不得让进程崩溃
            android.util.Log.w(TAG, "封面加载失败：" + url, e);
        }
        if (bitmap != null) {
            memoryCache.put(memKey, bitmap);
        }
        synchronized (inflight) {
            if (inflight.get(memKey) == task) {
                inflight.remove(memKey);
            }
        }
        final Bitmap result = bitmap;
        mainHandler.post(() -> deliver(url, task, result));
    }

    private void deliver(String url, Inflight task, @Nullable Bitmap bitmap) {
        for (WeakReference<ImageView> ref : task.views) {
            ImageView v = ref.get();
            if (v == null || bitmap == null) {
                continue;
            }
            // 代际校验：控件已被复用到其它 URL 时丢弃本次结果
            if (!url.equals(v.getTag())) {
                continue;
            }
            try {
                v.setImageBitmap(bitmap);
            } catch (Exception e) {
                android.util.Log.w(TAG, "设置封面位图失败，已忽略", e);
            }
        }
        for (Callback callback : task.callbacks) {
            try {
                callback.onResult(bitmap != null);
            } catch (Exception e) {
                android.util.Log.w(TAG, "封面回调执行失败，已忽略", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 三级缓存读取
    // ------------------------------------------------------------------

    /** 磁盘 → 网络 → 解码，返回可直接上屏的位图 */
    @Nullable
    private Bitmap loadBitmap(String url, int target) {
        byte[] data = readDisk(url);
        if (data == null) {
            data = fetch(url, target);
            if (data != null) {
                writeDisk(url, data);
            }
        }
        return decode(data, target);
    }

    /**
     * 回调统一切回主线程，且<b>始终经 {@code post} 投递</b>：
     * 这样即便命中内存缓存，回调也不会同步发生在 {@code onBindViewHolder} 执行栈内，
     * 从根源上避免「布局计算中刷新列表」导致的 {@code IllegalStateException} 闪退。
     */
    private void dispatch(@Nullable final Callback callback, final boolean success) {
        if (callback == null) {
            return;
        }
        mainHandler.post(() -> {
            try {
                callback.onResult(success);
            } catch (Exception e) {
                android.util.Log.w(TAG, "封面回调执行失败，已忽略", e);
            }
        });
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    @Nullable
    private byte[] fetch(String url, int target) {
        String requestUrl = withCdnSize(url, target);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(requestUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "WikiUploader/1.1.2 (Android; PublicWiki)");
            conn.setRequestProperty("Accept", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ACCEPT_IMAGE_AVIF : ACCEPT_IMAGE_WEBP);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream is = conn.getInputStream()) {
                return readAll(is);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 给网易云 CDN 地址附加 {@code ?param=WyH} 缩放参数。
     *
     * <p>服务端直接返回缩略图，省掉移动网络下的大图流量，也省掉客户端的降采样耗时。
     * 仅对白名单内的 CDN 域名生效；地址已带查询串（可能是防盗链签名）时保持原样。
     */
    static String withCdnSize(String url, int target) {
        if (target <= 0 || url == null || url.indexOf('?') >= 0) {
            return url;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        boolean cdn = false;
        for (String suffix : CDN_RESIZE_SUFFIX) {
            if (lower.contains(suffix)) {
                cdn = true;
                break;
            }
        }
        if (!cdn) {
            return url;
        }
        return url + "?param=" + target + "y" + target;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int len;
        while ((len = is.read(buf)) != -1) {
            total += len;
            if (total > MAX_BITMAP_BYTES) {
                throw new IllegalStateException("图片过大");
            }
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    @Nullable
    private static Bitmap decode(@Nullable byte[] data, int target) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeSampleSize(bounds, target);
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static int computeSampleSize(BitmapFactory.Options bounds, int target) {
        int sample = 1;
        if (target <= 0) {
            return 1;
        }
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }

    // ------------------------------------------------------------------
    // 磁盘缓存（LRU：按最后访问时间淘汰）
    // ------------------------------------------------------------------

    /** 内存缓存键：同一 URL 的不同目标尺寸各自成键，避免小图污染大图请求 */
    private static String memoryKey(String url, int target) {
        return url + "@" + target;
    }

    @Nullable
    private File diskCacheDir() {
        File dir = diskDir;
        if (dir != null) {
            return dir;
        }
        Context context = appContext;
        if (context == null) {
            return null;
        }
        synchronized (this) {
            if (diskDir == null) {
                File created = new File(context.getCacheDir(), DISK_DIR_NAME);
                if (!created.exists() && !created.mkdirs()) {
                    return null;
                }
                diskDir = created;
            }
            return diskDir;
        }
    }

    /** 用 URL 的哈希 + 长度做文件名，避免非法字符与同名冲突 */
    private static String diskKey(String url) {
        return Integer.toHexString(url.hashCode()) + "-" + url.length() + ".img";
    }

    @Nullable
    private byte[] readDisk(String url) {
        File dir = diskCacheDir();
        if (dir == null) {
            return null;
        }
        File file = new File(dir, diskKey(url));
        if (!file.isFile()) {
            return null;
        }
        long length = file.length();
        if (length <= 0 || length > MAX_BITMAP_BYTES) {
            // 残缺或超限的缓存直接丢弃
            file.delete();
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) length];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            if (read < data.length) {
                file.delete();
                return null;
            }
            // 触达时间即 LRU 顺序
            file.setLastModified(System.currentTimeMillis());
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeDisk(String url, byte[] data) {
        File dir = diskCacheDir();
        if (dir == null || data == null || data.length == 0) {
            return;
        }
        File target = new File(dir, diskKey(url));
        File temp = new File(dir, target.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(data);
            }
        } catch (Exception e) {
            temp.delete();
            return;
        }
        if (!temp.renameTo(target)) {
            // 并发写入时目标可能已存在，直接丢弃临时文件
            temp.delete();
            return;
        }
        trimDiskIfNeeded();
    }

    /** 磁盘占用超过上限时，按最后访问时间淘汰到约 3/4 水位 */
    private void trimDiskIfNeeded() {
        File dir = diskCacheDir();
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length < 8) {
            return;
        }
        long total = 0L;
        for (File file : files) {
            total += file.length();
        }
        if (total <= MAX_DISK_BYTES) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long target = MAX_DISK_BYTES * 3 / 4;
        for (File file : files) {
            if (total <= target) {
                break;
            }
            long length = file.length();
            if (file.delete()) {
                total -= length;
            }
        }
    }
}
