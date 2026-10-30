package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UploadDataProviders;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 Chromium 网络栈（Cronet）发 lucida 请求。
 *
 * <p>Cloudflare 的 {@code cf_clearance} 与 TLS/JA3、HTTP2 参数、UA、IP 绑定。
 * {@code HttpURLConnection} 走 Conscrypt，指纹与浏览器不一致，即使带上通行证也会被判新客户端。
 * Cronet 与 WebView 用的是同一套 BoringSSL 与 SSLConfig，指纹接近，才有机会直接放行。
 *
 * <p>Cronet 来自 Play 服务（dynamite 动态加载，不增加包体）；设备没有可用提供程序时
 * {@link #available()} 返回 {@code false}，调用方退回原生栈或浏览器通道。
 */
final class CronetHttp {

    private static final String TAG = "CronetHttp";
    private static final String BASE = "https://lucida.to";
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_MEMORY_BYTES = 4 * 1024 * 1024;
    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)");

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "cronet-callback");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 下载「连续无数据」阈值。
     *
     * <p>响应体停止到达后 Cronet 既不给数据也不给终态回调，只靠 30 分钟的整段超时会让用户
     * 一直停在某个百分比上；改成按「多久没收到字节」判定，弱网下也不会被总时长误伤。
     */
    static final long IDLE_TIMEOUT_MS = 60_000L;
    /** 整段下载的硬上限，只作为最后兜底 */
    private static final long MAX_DOWNLOAD_MS = 30 * 60_000L;
    /** 看门狗线程：每秒检查一次无数据时长 */
    private static final ScheduledThreadPoolExecutor WATCHDOG =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "cronet-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile CronetEngine engine;
    private static volatile boolean engineTried;
    private static volatile String userAgent;
    private static Context appContext;

    private CronetHttp() {
    }

    /** 记录 Application Context：引擎从 Play 服务加载时需要它。 */
    static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    static boolean available() {
        return engine() != null;
    }

    private static CronetEngine engine() {
        CronetEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (CronetHttp.class) {
            if (engine != null) {
                return engine;
            }
            if (engineTried) {
                return null;
            }
            Context context = appContext;
            if (context == null) {
                // 还没 init：不能把「没准备好」记成「试过了」，否则本进程再也拿不到引擎
                return null;
            }
            engineTried = true;
            try {
                CountDownLatch installed = new CountDownLatch(1);
                com.google.android.gms.net.CronetProviderInstaller.installProvider(context)
                        .addOnCompleteListener(task -> installed.countDown());
                if (!installed.await(20, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Play 服务未在 20s 内就绪");
                    return null;
                }
                engine = new CronetEngine.Builder(context)
                        .enableHttp2(true)
                        .enableBrotli(true)
                        .build();
                Log.w(TAG, "Cronet 就绪：" + engine.getVersionString());
            } catch (Throwable e) {
                // 没有 Play 服务或版本不匹配：保持原生栈，别再重试
                Log.w(TAG, "Cronet 不可用：" + e);
                engine = null;
            }
            return engine;
        }
    }

    /** 与 WebView 完全一致的 UA：通行证是按 UA 签发的，换一个就会重新挑战。 */
    private static String userAgent() {
        String ua = userAgent;
        if (ua != null) {
            return ua;
        }
        Context context = appContext;
        if (context == null) {
            return null;
        }
        try {
            ua = WebSettings.getDefaultUserAgent(context);
        } catch (Throwable e) {
            ua = null;
        }
        userAgent = ua;
        return ua;
    }

    static String request(String method, String url, String body) throws IOException {
        CronetEngine cronet = engine();
        if (cronet == null) {
            throw new IOException("cronet 不可用");
        }
        Call call = new Call(null, null);
        UrlRequest request = build(cronet, url, call, method, body).build();
        request.start();
        await(call, request, 90_000L);
        if (call.error != null) {
            throw new IOException(call.error);
        }
        String text = call.memory.toString(StandardCharsets.UTF_8.name());
        if (call.status < 200 || call.status >= 300) {
            throw new IOException("HTTP " + call.status + "：" + LucidaClient.brief(text));
        }
        return text;
    }

    static LucidaClient.Downloaded download(String url, File dir, LucidaClient.Progress progress)
            throws IOException {
        return download(url, dir, progress, null);
    }

    /**
     * 下载到临时文件。
     *
     * <p>除整段上限外还带「连续无数据」看门狗：超过 {@link #IDLE_TIMEOUT_MS} 没有收到任何字节就
     * 取消请求并抛出可读错误，调用方据此恢复按钮、删除半截文件。
     *
     * @param cancelled 外部取消信号（可为 null），置位后一秒内结束任务
     */
    static LucidaClient.Downloaded download(String url, File dir, LucidaClient.Progress progress,
                                            AtomicBoolean cancelled) throws IOException {
        CronetEngine cronet = engine();
        if (cronet == null) {
            throw new IOException("cronet 不可用");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        purgeStaleParts(dir, ".cronet-");
        File part = new File(dir, ".cronet-" + System.nanoTime() + ".part");
        Call call = new Call(part, progress);
        UrlRequest request = build(cronet, url, call, "GET", null).build();
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            boolean stop = cancelled != null && cancelled.get();
            if (!stop && !call.guard.checkExpired()) {
                return;
            }
            if (call.error == null) {
                call.error = stop ? "已取消下载"
                        : "下载超时：连续 " + (IDLE_TIMEOUT_MS / 1000) + " 秒没有收到数据";
            }
            if (!stop) {
                Log.w(TAG, "无数据超时，取消下载（host=" + hostOf(url)
                        + "，已写 " + call.written + " 字节）");
            }
            try {
                request.cancel();
            } catch (Throwable ignored) {
                // 请求已结束
            }
        }, 1L, 1L, TimeUnit.SECONDS);
        try {
            request.start();
            await(call, request, MAX_DOWNLOAD_MS);
        } finally {
            watchdog.cancel(false);
        }
        if (!call.toFile || call.error != null) {
            part.delete();
            if (call.error != null) {
                throw new IOException(call.error);
            }
            if (call.status < 200 || call.status >= 300) {
                throw new IOException("下载失败 HTTP " + call.status + "："
                        + LucidaClient.brief(call.memory.toString()));
            }
            throw new IOException("服务返回了网页而不是文件");
        }
        if (call.written <= 0) {
            part.delete();
            throw new IOException("下载结果为空");
        }
        return new LucidaClient.Downloaded(part, call.contentType);
    }

    /**
     * 允许落盘的 Content-Type：只排除网页 / JSON / XML 这类错误页。
     *
     * <p>「HTTP 200 + JSON 空壳」曾被当成音频落盘，最终得到一个无法解码的文件；
     * 音频类型在不同服务端写法不一（audio/*、application/octet-stream、application/mp4），
     * 因此采用黑名单而不是白名单。
     */
    static boolean isDownloadableType(String type) {
        if (type == null) {
            return true;
        }
        String value = type.toLowerCase(Locale.ROOT).trim();
        if (value.isEmpty()) {
            return true;
        }
        return !value.startsWith("text/")
                && !value.startsWith("application/json")
                && !value.startsWith("application/xml")
                && !value.startsWith("application/xhtml");
    }

    /** 只取 host 用于脱敏日志，不输出完整 URL（路径里可能带一次性 handoff）。 */
    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "-";
        }
    }

    /**
     * 清理上次异常退出（进程被杀 / 崩溃）留下的半截文件。
     *
     * <p>只删超过 24 小时的：正在下载的临时文件不会被误删，长期堆积的垃圾能自动回收。
     */
    static void purgeStaleParts(File dir, String prefix) {
        if (dir == null || prefix == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - 24 * 60 * 60_000L;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(prefix) && name.endsWith(".part")
                    && file.isFile() && file.lastModified() < deadline && file.delete()) {
                Log.w(TAG, "清理残留临时文件：" + name);
            }
        }
    }

    private static UrlRequest.Builder build(CronetEngine cronet, String url, Call call,
                                            String method, String body) {
        UrlRequest.Builder builder = cronet.newUrlRequestBuilder(url, call, EXECUTOR).disableCache();
        String ua = userAgent();
        builder.addHeader("Accept", "*/*");
        builder.addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        builder.addHeader("Referer", BASE + "/");
        builder.addHeader("Origin", BASE);
        builder.addHeader("Sec-Fetch-Site", isLucidaOrigin(url) ? "same-origin" : "same-site");
        builder.addHeader("Sec-Fetch-Mode", "cors");
        builder.addHeader("Sec-Fetch-Dest", "empty");
        if (ua != null) {
            builder.addHeader("User-Agent", ua);
            addClientHints(builder, ua);
        }
        String cookie = cookieFor(url);
        if (cookie != null && !cookie.isEmpty()) {
            builder.addHeader("Cookie", cookie);
        }
        builder.setHttpMethod(method);
        if (body != null) {
            builder.addHeader("Content-Type", "application/json");
            builder.setUploadDataProvider(
                    UploadDataProviders.create(body.getBytes(StandardCharsets.UTF_8)), EXECUTOR);
        }
        return builder;
    }

    /** sec-ch-ua 必须与 UA 里的 Chrome 大版本一致，否则会被判成伪造。 */
    private static void addClientHints(UrlRequest.Builder builder, String ua) {
        Matcher matcher = CHROME_VERSION.matcher(ua);
        if (!matcher.find()) {
            return;
        }
        String major = matcher.group(1);
        builder.addHeader("sec-ch-ua", "\"Chromium\";v=\"" + major
                + "\", \"Not(A:Brand\";v=\"24\", \"Android WebView\";v=\"" + major + "\"");
        builder.addHeader("sec-ch-ua-mobile", "?1");
        builder.addHeader("sec-ch-ua-platform", "\"Android\"");
    }

    private static boolean isLucidaOrigin(String url) {
        return url != null && (url.startsWith("https://lucida.to/"));
    }

    private static String cookieFor(String url) {
        try {
            return CookieManager.getInstance().getCookie(url);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void await(Call call, UrlRequest request, long timeoutMs) throws IOException {
        try {
            if (!call.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                try {
                    request.cancel();
                } catch (Throwable ignored) {
                    // 已结束
                }
                throw new IOException("请求超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                request.cancel();
            } catch (Throwable ignored) {
                // 已结束
            }
            throw new IOException("已中断", e);
        }
    }

    /** 一次请求的回调：把响应体收进内存或直接写盘。 */
    private static final class Call extends UrlRequest.Callback {

        final CountDownLatch latch = new CountDownLatch(1);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES);
        final ByteArrayOutputStream memory = new ByteArrayOutputStream();
        /** 连续无数据看门狗：每次收到响应头 / 数据块都会续期 */
        final DownloadIdleGuard guard = DownloadIdleGuard.ofSystemClock(IDLE_TIMEOUT_MS);
        private final File target;
        private final LucidaClient.Progress progress;

        volatile int status;
        volatile long written;
        volatile String contentType = "";
        volatile String error;
        volatile boolean toFile;
        private OutputStream out;
        private long total = -1;
        private int lastPercent = -1;
        private boolean finished;

        Call(File target, LucidaClient.Progress progress) {
            this.target = target;
            this.progress = progress;
        }

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String url) {
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            status = info.getHttpStatusCode();
            contentType = header(info, "content-type");
            total = parseLong(header(info, "content-length"));
            guard.touch();
            boolean ok = status >= 200 && status < 300
                    && isDownloadableType(contentType);
            toFile = target != null && ok;
            if (toFile) {
                try {
                    out = new FileOutputStream(target);
                } catch (IOException e) {
                    error = "写入文件失败：" + e.getMessage();
                    request.cancel();
                    return;
                }
            }
            request.read(buffer);
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer chunk) {
            chunk.flip();
            try {
                int length = chunk.remaining();
                if (toFile) {
                    byte[] data = new byte[length];
                    chunk.get(data);
                    out.write(data);
                    written += length;
                    guard.touch();
                    reportProgress();
                } else {
                    if (memory.size() + length > MAX_MEMORY_BYTES) {
                        throw new IOException("响应体过大");
                    }
                    byte[] data = new byte[length];
                    chunk.get(data);
                    memory.write(data);
                }
            } catch (IOException e) {
                error = e.getMessage() == null ? "读取失败" : e.getMessage();
                request.cancel();
                return;
            } finally {
                chunk.clear();
            }
            request.read(buffer);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            // 服务端声明了长度但提前收尾：当作不完整，避免把半首歌当成功写进最终文件名
            if (toFile && total > 0 && written < total && error == null) {
                error = "下载不完整（" + written + "/" + total + " 字节）";
            }
            finish();
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException exception) {
            error = exception == null ? "网络错误" : exception.getMessage();
            finish();
        }

        @Override
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {
            if (error == null) {
                error = "请求已取消";
            }
            finish();
        }

        private void reportProgress() {
            if (progress == null || total <= 0) {
                return;
            }
            int percent = (int) Math.min(100L, written * 100 / total);
            if (percent != lastPercent) {
                lastPercent = percent;
                progress.onPercent(percent);
            }
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已写入的内容
                }
                out = null;
            }
            latch.countDown();
        }

        private static String header(UrlResponseInfo info, String name) {
            List<Map.Entry<String, String>> headers = info.getAllHeadersAsList();
            for (Map.Entry<String, String> entry : headers) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return "";
        }

        private static long parseLong(String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (Exception e) {
                return -1;
            }
        }
    }
}
