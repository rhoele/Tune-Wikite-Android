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

import java.io.File;
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

    /**
     * 单张图片允许下载的上限。
     *
     * <p>这里只拦截明显异常的响应（例如被错误地当成图片返回的大文件），
     * 不再用「4MB」这种体积阈值直接判失败：不少封面原图（尤其是聚合接口
     * 直接给出的原图）体积就超过 4MB，按体积拒绝会让整张卡片一直停在占位图。
     * 真正的内存控制交给按目标尺寸采样解码。
     */
    private static final int MAX_DOWNLOAD_BYTES = 24 * 1024 * 1024;
    /** 超过该体积的图片照常解码上屏，但不进入磁盘缓存，避免把缓存挤爆 */
    private static final int MAX_DISK_ENTRY_BYTES = 8 * 1024 * 1024;
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

    /** 域名白名单校验：按 URL 解析出的 host 精确比对，只允许 HTTPS。 */
    public static boolean isAllowed(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://")) {
            return false;
        }
        String host;
        try {
            host = java.net.URI.create(lower).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String suffix : ALLOWED_HOST_SUFFIX) {
            if (suffix.startsWith(".")) {
                String bare = suffix.substring(1);
                if (host.equals(bare) || host.endsWith(suffix)) {
                    return true;
                }
            } else if (host.equals(suffix)) {
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
     * http 地址统一升级到 https。
     *
     * <p>白名单只放行 https，绝不发起明文请求；但部分旧接口仍会返回 http 的 CDN 链接，
     * 直接拒绝会让这些封面变成空白，因此在校验前先升级协议。
     */
    private static String httpsize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + trimmed.substring(7);
        }
        return trimmed;
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
        final String safeUrl = httpsize(url);
        if (!isAllowed(safeUrl)) {
            view.setTag(null);
            view.setImageDrawable(null);
            dispatch(callback, false);
            return;
        }

        final String memKey = memoryKey(safeUrl, target);
        // tag 用（升级后的）URL：换页复用时只需比对 URL 即可判断结果是否已过期
        final boolean sameUrl = safeUrl.equals(view.getTag());
        view.setTag(safeUrl);

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
            executor.execute(() -> decodeInto(memKey, safeUrl, target, task));
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
        for (final String raw : urls) {
            final String url = httpsize(raw);
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

    /**
     * 磁盘 → 网络 → 采样解码，返回可直接上屏的位图。
     *
     * <p>全程按文件流处理：下载直接落临时文件、解码用
     * {@link BitmapFactory#decodeFile(String, BitmapFactory.Options)} 两次读取（先取尺寸，
     * 再按采样率解码），因此大体积原图也不会被整块读进内存。
     */
    @Nullable
    private Bitmap loadBitmap(String url, int target) {
        File cached = diskFile(url, target);
        if (cached != null) {
            Bitmap bitmap = decodeFile(cached, target);
            if (bitmap != null) {
                // 触达时间即 LRU 顺序
                cached.setLastModified(System.currentTimeMillis());
                return bitmap;
            }
            // 缓存文件损坏 / 格式变化：删掉后重新下载
            cached.delete();
        }
        File temp = download(url, target);
        if (temp == null) {
            return null;
        }
        Bitmap bitmap = decodeFile(temp, target);
        if (bitmap == null) {
            temp.delete();
            return null;
        }
        keepInDiskCache(url, target, temp);
        return bitmap;
    }

    /** 下载结果落盘：体积正常的转为正式缓存文件，过大的解码后直接丢弃临时文件。 */
    private void keepInDiskCache(String url, int target, File temp) {
        try {
            if (temp.length() <= 0 || temp.length() > MAX_DISK_ENTRY_BYTES) {
                temp.delete();
                return;
            }
            File dir = diskCacheDir();
            if (dir == null) {
                temp.delete();
                return;
            }
            File targetFile = new File(dir, diskKey(url, target));
            if (temp.renameTo(targetFile)) {
                trimDiskIfNeeded();
            } else {
                temp.delete();
            }
        } catch (Exception e) {
            temp.delete();
        }
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

    /**
     * 下载图片到临时文件。
     *
     * <p>边读边写，不在内存里拼接完整响应体；超过 {@link #MAX_DOWNLOAD_BYTES} 视为异常响应，
     * 停止下载并删除临时文件。失败返回 {@code null}。
     */
    @Nullable
    private File download(String url, int target) {
        String requestUrl = withCdnSize(url, target);
        File temp = newTempFile();
        if (temp == null) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(requestUrl).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "WikiUploader/1.2.2 (Android; PublicWiki)");
            conn.setRequestProperty("Accept", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ACCEPT_IMAGE_AVIF : ACCEPT_IMAGE_WEBP);
            if (conn.getResponseCode() != 200) {
                temp.delete();
                return null;
            }
            long total = 0L;
            try (InputStream is = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[16384];
                int len;
                while ((len = is.read(buf)) != -1) {
                    total += len;
                    if (total > MAX_DOWNLOAD_BYTES) {
                        android.util.Log.w(TAG, "图片响应超过上限，已放弃：" + url);
                        break;
                    }
                    out.write(buf, 0, len);
                }
            }
            if (total <= 0 || total > MAX_DOWNLOAD_BYTES) {
                temp.delete();
                return null;
            }
            return temp;
        } catch (Exception e) {
            temp.delete();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 图片临时文件：优先放在封面缓存目录，未注入上下文时退回系统临时目录。 */
    @Nullable
    private File newTempFile() {
        try {
            File dir = diskCacheDir();
            if (dir == null) {
                Context context = appContext;
                dir = context == null ? null : context.getCacheDir();
            }
            return File.createTempFile("cover-", ".tmp", dir);
        } catch (Exception e) {
            return null;
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
        boolean cdn = false;
        try {
            String host = java.net.URI.create(url.trim().toLowerCase(Locale.ROOT)).getHost();
            if (host != null) {
                for (String suffix : CDN_RESIZE_SUFFIX) {
                    String bare = suffix.startsWith(".") ? suffix.substring(1) : suffix;
                    if (host.equals(bare) || (suffix.startsWith(".") && host.endsWith(suffix))) {
                        cdn = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return url;
        }
        if (!cdn) {
            return url;
        }
        return url + "?param=" + target + "y" + target;
    }

    /**
     * 从文件采样解码。
     *
     * <p>先只读尺寸，再按目标边长选采样率解码，因此原图再大也只解出接近列表尺寸的位图；
     * 采样后仍 OOM 时再降一档重试一次。
     */
    @Nullable
    private static Bitmap decodeFile(File file, int target) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, target);
            try {
                return decodeFileWithSample(file, sample);
            } catch (OutOfMemoryError first) {
                android.util.Log.w(TAG, "封面解码内存不足，降采样重试：" + file.getName());
                try {
                    return decodeFileWithSample(file, Math.min(sample * 2, 64));
                } catch (OutOfMemoryError | Exception ignored) {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeFileWithSample(File file, int sample) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /**
     * 按目标边长计算采样率（2 的幂）。
     *
     * <p>包内可见以便单测固化策略：目标未知（{@code <= 0}）时不采样；
     * 允许解码结果略大于目标（两倍以内），避免过度模糊。</p>
     */
    static int sampleSizeFor(int width, int height, int target) {
        if (target <= 0 || width <= 0 || height <= 0) {
            return 1;
        }
        int maxSide = Math.max(width, height);
        int sample = 1;
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

    /**
     * 磁盘缓存键：URL 的哈希 + 长度 + 目标边长。
     *
     * <p>目标边长必须参与：CDN 会按 {@code ?param=N y N} 返回不同分辨率的图，
     * 若只按原始 URL 落盘，先来的小图会长期挡住之后的高清请求。
     */
    static String diskKey(String url, int target) {
        String base = url + "@" + Math.max(0, target);
        return Integer.toHexString(base.hashCode()) + "-" + url.length() + "-"
                + Math.max(0, target) + ".img";
    }

    /** 磁盘缓存文件（不存在时返回 null）。 */
    @Nullable
    private File diskFile(String url, int target) {
        File dir = diskCacheDir();
        if (dir == null) {
            return null;
        }
        File file = new File(dir, diskKey(url, target));
        if (!file.isFile() || file.length() <= 0) {
            return null;
        }
        return file;
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
