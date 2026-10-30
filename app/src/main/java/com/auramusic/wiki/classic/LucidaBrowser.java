package com.auramusic.wiki.classic;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * lucida.to 的浏览器内请求通道。
 *
 * <p>Cloudflare 下发的 {@code cf_clearance} 与浏览器指纹（UA + TLS/HTTP2 指纹 + IP）绑定。
 * 用 Java 自带 HTTP 栈即使带上这张通行证，也会被当成另一个客户端直接 403 ——
 * 这正是 cloudscraper / nodriver 这类方案的核心：<b>挑战与业务请求必须出自同一个浏览器</b>。
 * App 里最可靠的等价做法是把请求放回 WebView 的页面上下文执行：
 * <ul>
 *   <li>JSON 接口 → 页面内 {@code fetch()}，结果经 JS 桥回传；</li>
 *   <li>音频文件 → 页面内流式读取响应体，按块 base64 回传并落盘，
 *       需要过盾的地址不再让原生栈去碰。</li>
 * </ul>
 *
 * <p>WebView 常驻在内容容器最底层（被不透明的业务界面盖住，Chromium 不会把它当后台页限流），
 * 一轮下载结束即销毁。
 */
final class LucidaBrowser implements LucidaClient.Transport, AutoCloseable {

    private static final String HOME = "https://lucida.to/";
    private static final String TAG = "LucidaBrowser";
    private static final String JS_BRIDGE = "LucidaBridge";
    private static final long REQUEST_TIMEOUT_MS = 90_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 30 * 60_000L;
    /** 每块回传的原始字节数：base64 后约 256KB，兼顾吞吐与 JS 桥单次开销 */
    private static final int CHUNK_BYTES = 192 * 1024;

    /**
     * 注入页面上下文的工具函数。
     *
     * <p>每次调用都重新定义一次：页面若被 Cloudflare 重载，旧上下文会失效，
     * 幂等定义比「一次性注入 + 失效检测」更省事。
     */
    private static final String JS_HELPER =
            "window.__lucidaFetch=function(id,url,method,body){"
                    + "var run=async function(creds,retry){"
                    + "try{"
                    + "var o={method:method,credentials:creds,cache:'no-store',"
                    + "headers:{'Accept':'*/*'}};"
                    + "if(body!==null){o.headers['Content-Type']='application/json';o.body=body;}"
                    + "var r=await fetch(url,o);"
                    + "var t=await r.text();"
                    + JS_BRIDGE + ".onJson(id,r.status,r.headers.get('content-type')||'',t);"
                    + "}catch(e){"
                    // 轮询地址在 <server>.lucida.to 子域，对方只回 ACAO: *，
                    // 带凭据会被浏览器判 CORS 失败；这个接口不需要 cookie，去掉凭据重试
                    + "if(retry){return run('omit',false);}"
                    + "throw e;}"
                    + "};"
                    + "run('include',true).catch(function(e){"
                    + JS_BRIDGE + ".onError(id,String(e));});};"
                    + "window.__lucidaDownload=function(id,url,chunk){"
                    + "(async function(){"
                    + "var sent=0;"
                    + "var run=async function(creds,retry){"
                    + "try{"
                    + "var r=await fetch(url,{credentials:creds,cache:'no-store'});"
                    + "if(!r.ok){throw new Error('HTTP '+r.status+' '+"
                    + "(r.headers.get('content-type')||''));}"
                    + JS_BRIDGE + ".onMeta(id,+(r.headers.get('content-length')||0),"
                    + "r.headers.get('content-type')||'');"
                    + "var reader=r.body.getReader(),parts=[],size=0;"
                    + "var flush=function(){"
                    + "var all=new Uint8Array(size),o=0;"
                    + "for(var i=0;i<parts.length;i++){all.set(parts[i],o);o+=parts[i].length;}"
                    + "var bin='',STEP=0x8000;"
                    + "for(var j=0;j<all.length;j+=STEP){"
                    + "bin+=String.fromCharCode.apply(null,all.subarray(j,j+STEP));}"
                    + JS_BRIDGE + ".onChunk(id,btoa(bin));parts=[];size=0;sent++;};"
                    + "for(;;){var s=await reader.read();if(s.done)break;"
                    + "parts.push(s.value);size+=s.value.length;"
                    + "if(size>=chunk)flush();}"
                    + "if(size>0)flush();"
                    + JS_BRIDGE + ".onDone(id);"
                    + "}catch(e){"
                    // 下载地址在 lucida.to 的子域：若对方只回 Access-Control-Allow-Origin: *，
                    // 带凭据的跨域请求会被浏览器直接拒绝，这里去掉凭据重试一次
                    + "if(retry&&sent===0){return run('omit',false);}"
                    + "throw e;}"
                    + "};"
                    + "try{await run('include',true);}"
                    + "catch(e){" + JS_BRIDGE + ".onError(id,String(e));}"
                    + "})();};";

    private final Activity activity;
    private final WebView web;
    private final Dialog dialog;
    private final Map<String, JsonCall> jsonCalls = new ConcurrentHashMap<>();
    private final Map<String, FileCall> fileCalls = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean ready;
    private volatile boolean closed;

    private LucidaBrowser(Activity activity, WebView web, Dialog dialog) {
        this.activity = activity;
        this.web = web;
        this.dialog = dialog;
    }

    /**
     * 正在使用的浏览器通道。
     *
     * <p>兜底通道是挂在 Activity 窗口上的 WebView + Dialog，下载线程退出前不会自己消失。
     * 用户中途离开页面时由界面侧调用 {@link #closeAll()} 主动收尾，避免窗口泄漏到超时。
     */
    private static final java.util.Set<LucidaBrowser> ACTIVE =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** 关闭当前所有浏览器通道（页面离开 / Activity 销毁时调用，可重复调用）。 */
    static void closeAll() {
        LucidaBrowser[] list;
        synchronized (ACTIVE) {
            list = ACTIVE.toArray(new LucidaBrowser[0]);
        }
        for (LucidaBrowser browser : list) {
            try {
                browser.close();
            } catch (Exception ignored) {
                // 已关闭
            }
        }
    }

    // ------------------------------------------------------------------
    // 建立会话
    // ------------------------------------------------------------------

    /**
     * 打开 WebView 并等页面真正可用（托管挑战已通过）。
     *
     * <p>就绪判据不是「cookie 里出现 cf_clearance」而是<b>页面内真的能 fetch 到首页且返回 200</b>：
     * lucida 现在会下发需要交互的挑战，只等 cookie 容易在挑战进行到一半时就误判通过。
     *
     * @return 可用的通道；调用方负责 {@link #close()}
     */
    static LucidaBrowser open(Activity activity, long timeoutMs) throws IOException {
        if (activity == null || activity.isFinishing()) {
            throw new IOException("没有可用的界面");
        }
        final CountDownLatch created = new CountDownLatch(1);
        final LucidaBrowser[] holder = new LucidaBrowser[1];
        final IOException[] failure = new IOException[1];
        activity.runOnUiThread(() -> {
            try {
                holder[0] = create(activity);
            } catch (Exception e) {
                failure[0] = new IOException("无法创建浏览器会话", e);
            } finally {
                created.countDown();
            }
        });
        try {
            created.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已中断", e);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        LucidaBrowser browser = holder[0];
        if (browser == null) {
            throw new IOException("无法创建浏览器会话");
        }
        ACTIVE.add(browser);
        browser.awaitReady(timeoutMs);
        return browser;
    }

    /** 主线程：建 WebView、挂最底层、起验证页。 */
    private static LucidaBrowser create(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            throw new IllegalStateException("内容容器不存在");
        }
        WebView web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // UA 与 CookieManager 都保持默认：页面内的 fetch 与挑战出自同一个浏览器实例，
        // 不再需要把 UA / cookie 抄给原生栈
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.TRANSPARENT);
        Dialog dialog = buildOverlay(activity, content, web);
        LucidaBrowser browser = new LucidaBrowser(activity, web, dialog);
        web.addJavascriptInterface(browser.new Bridge(), JS_BRIDGE);
        content.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.loadUrl(HOME);

        // 就绪探测放到后台线程：request() 会阻塞等待 JS 回传，不能占着主线程
        Thread probe = new Thread(() -> browser.probeUntilReady(), "lucida-probe");
        probe.setDaemon(true);
        probe.start();
        return browser;
    }

    /** 验证期间只弹一张小卡片；点卡片把验证页翻到最前面（挑战偶尔需要人点一下）。 */
    private static Dialog buildOverlay(Activity activity, ViewGroup content, WebView web) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (density * 20);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable cardBg =
                new android.graphics.drawable.GradientDrawable();
        cardBg.setCornerRadius(density * 18);
        cardBg.setColor(themeColor(card,
                com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF3A3A42));
        card.setBackground(cardBg);
        card.setElevation(density * 8);
        card.setMinimumWidth((int) (density * 220));

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        try {
            spinner.getIndeterminateDrawable().setTint(themeColor(card,
                    androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4));
        } catch (Exception ignored) {
            // 个别 ROM 的 indeterminate drawable 不支持着色
        }
        int spinnerSize = (int) (density * 28);
        card.addView(spinner, new LinearLayout.LayoutParams(spinnerSize, spinnerSize));

        TextView banner = new TextView(activity);
        banner.setText(R.string.sc_lucida_verifying);
        banner.setTextColor(themeColor(card,
                com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        banner.setTextSize(13f);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(0, pad / 2, 0, 0);
        card.addView(banner);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCancelable(false);
        card.setOnClickListener(v -> {
            // 关掉卡片而不是只把它设成 GONE：Dialog 窗口不透明区域仍会吃掉触摸，
            // 挑战需要人工点一下时得让页面真的能操作
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
                // 窗口已销毁
            }
            content.bringChildToFront(web);
            web.requestLayout();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /** 轮询探测首页，直到返回正常页面或超时；成功后关掉提示卡片。 */
    private void probeUntilReady() {
        long deadline = SystemClock.uptimeMillis() + 25_000L;
        long sleep = 500L;
        while (!closed) {
            try {
                String body = request("GET", HOME, null, 8_000L);
                if (looksLoaded(body)) {
                    ready = true;
                    dismissOverlay();
                    return;
                }
            } catch (IOException ignored) {
                // 挑战还没过完：继续等
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                return;
            }
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sleep = Math.min(1500L, sleep + 200L);
        }
    }

    /**
     * 是否已经是真正的页面。
     *
     * <p>不能用「含 challenge-platform」判断：正常页面同样会带这段脚本。
     * 只认挑战页的特征文案 —— 挑战页本身也常以 200 返回，单看状态码会误判。
     */
    private static boolean looksLoaded(String body) {
        if (body == null || body.length() < 500) {
            return false;
        }
        String text = body.toLowerCase(java.util.Locale.ROOT);
        return !text.contains("cf-browser-verification")
                && !text.contains("just a moment")
                && !text.contains("enable javascript and cookies");
    }

    private void awaitReady(long timeoutMs) throws IOException {
        long deadline = SystemClock.uptimeMillis() + Math.max(1_000L, timeoutMs);
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready) {
                return;
            }
            if (closed) {
                break;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        close();
        throw new IOException("lucida 浏览器验证未完成");
    }

    private void dismissOverlay() {
        activity.runOnUiThread(() -> {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
                // 页面已销毁
            }
        });
    }

    // ------------------------------------------------------------------
    // LucidaClient.Transport
    // ------------------------------------------------------------------

    @Override
    public String request(String method, String url, String body) throws IOException {
        return request(method, url, body, REQUEST_TIMEOUT_MS);
    }

    private String request(String method, String url, String body, long timeoutMs)
            throws IOException {
        if (closed) {
            throw new IOException("浏览器通道已关闭");
        }
        String id = "j" + sequence.incrementAndGet();
        JsonCall call = new JsonCall();
        jsonCalls.put(id, call);
        try {
            evaluate(JS_HELPER + "window.__lucidaFetch(" + JSONObject.quote(id) + ","
                    + JSONObject.quote(url) + "," + JSONObject.quote(method) + ","
                    + (body == null ? "null" : JSONObject.quote(body)) + ");");
            if (!call.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("lucida 请求超时");
            }
            if (call.error != null) {
                throw new IOException(call.error);
            }
            if (call.status < 200 || call.status >= 300) {
                throw new IOException("HTTP " + call.status + "：" + brief(call.body));
            }
            return call.body == null ? "" : call.body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已中断", e);
        } finally {
            jsonCalls.remove(id);
        }
    }

    @Override
    public LucidaClient.Downloaded download(String url, File dir, LucidaClient.Progress progress,
                                            java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws IOException {
        if (closed) {
            throw new IOException("浏览器通道已关闭");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        String id = "f" + sequence.incrementAndGet();
        CronetHttp.purgeStaleParts(dir, ".lucida-");
        File part = new File(dir, ".lucida-" + id + ".part");
        FileCall call = new FileCall(part, progress);
        fileCalls.put(id, call);
        if (progress != null) {
            progress.onStatus("正在通过浏览器通道下载…");
        }
        boolean success = false;
        try {
            // 文件接口不回 CORS 头，跨域读不到响应体；先把文档切到同一 host 上，改走同源
            ensureOrigin(hostOf(url));
            evaluate(JS_HELPER + "window.__lucidaDownload(" + JSONObject.quote(id) + ","
                    + JSONObject.quote(url) + "," + CHUNK_BYTES + ");");
            awaitFile(call, cancelled);
            if (call.error != null) {
                throw new IOException(call.error);
            }
            if (call.written <= 0) {
                throw new IOException("下载结果为空");
            }
            if (call.total > 0 && call.written < call.total) {
                throw new IOException("下载不完整（" + call.written + "/" + call.total + " 字节）");
            }
            success = true;
            return new LucidaClient.Downloaded(call.file, call.contentType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已中断", e);
        } finally {
            fileCalls.remove(id);
            call.closeQuietly();
            if (!success) {
                // 失败时不留半截文件
                part.delete();
            }
        }
    }

    /**
     * 等待分块回传结束。
     *
     * <p>页面内 fetch 停止推流时同样不会有任何回调（与原生下载同一类问题），
     * 因此按「连续无分块」判定超时，并支持外部取消。
     */
    private void awaitFile(FileCall call, java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws InterruptedException, IOException {
        long deadline = SystemClock.uptimeMillis() + DOWNLOAD_TIMEOUT_MS;
        while (true) {
            long remain = deadline - SystemClock.uptimeMillis();
            if (remain <= 0L) {
                call.error = "下载超时";
                return;
            }
            if (call.latch.await(Math.min(1000L, remain), TimeUnit.MILLISECONDS)) {
                return;
            }
            if (cancelled != null && cancelled.get()) {
                call.error = "已取消下载";
                call.closeQuietly();
                return;
            }
            if (call.guard.checkExpired()) {
                Log.w(TAG, "浏览器通道无分块超时，已放弃（id 已忽略，已写 "
                        + call.written + " 字节）");
                call.error = "下载超时：连续 " + (CronetHttp.IDLE_TIMEOUT_MS / 1000)
                        + " 秒没有收到数据";
                call.closeQuietly();
                return;
            }
        }
    }

    /** 主线程求值；JS 的回传通过 {@link Bridge} 回到各自等待者。 */
    private void evaluate(String js) throws IOException {
        if (closed) {
            throw new IOException("浏览器通道已关闭");
        }
        final CountDownLatch ack = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                if (!closed) {
                    web.evaluateJavascript(js, value -> ack.countDown());
                    return;
                }
            } catch (Exception ignored) {
                // WebView 已销毁
            }
            ack.countDown();
        });
        try {
            if (!ack.await(5, TimeUnit.SECONDS)) {
                throw new IOException("页面无响应");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("已中断", e);
        }
    }

    /** 取 URL 的 host；解析失败时返回 null。 */
    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把文档上下文切到指定 host。
     *
     * <p>lucida.to 与 {@code <server>.lucida.to} 是两个不同的源，而文件接口又不回 CORS 头，
     * 跨域 fetch 读不到 response body。这里让 WebView 先打开该 host 的页面，
     * 之后的请求就成了同源请求 —— 与浏览器里手动打开下载链接等价。
     */
    private void ensureOrigin(String host) throws IOException {
        if (host == null || host.isEmpty() || hostMatches(host)) {
            return;
        }
        activity.runOnUiThread(() -> web.loadUrl("https://" + host + "/"));
        long deadline = SystemClock.uptimeMillis() + 20_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (closed) {
                throw new IOException("浏览器通道已关闭");
            }
            if (hostMatches(host)) {
                return;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("已中断", e);
            }
        }
        throw new IOException("无法切换到 " + host);
    }

    private boolean hostMatches(String host) {
        String value = evalValue("location.host", 3_000L);
        return value != null && value.contains(host);
    }

    /** 求值并取返回值（只在需要读 JS 结果的场景用）。 */
    private String evalValue(String js, long timeoutMs) {
        final CountDownLatch ack = new CountDownLatch(1);
        final String[] out = new String[1];
        activity.runOnUiThread(() -> {
            try {
                web.evaluateJavascript(js, value -> {
                    out[0] = value;
                    ack.countDown();
                });
            } catch (Exception e) {
                ack.countDown();
            }
        });
        try {
            ack.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
        closed = true;
        ready = false;
        JsonCall[] jsons = jsonCalls.values().toArray(new JsonCall[0]);
        for (JsonCall call : jsons) {
            call.error = "浏览器通道已关闭";
            call.latch.countDown();
        }
        FileCall[] files = fileCalls.values().toArray(new FileCall[0]);
        for (FileCall call : files) {
            call.error = "浏览器通道已关闭";
            call.closeQuietly();
            call.file.delete();
            call.latch.countDown();
        }
        activity.runOnUiThread(() -> {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
                // 页面已销毁
            }
            try {
                if (web.getParent() instanceof ViewGroup) {
                    ((ViewGroup) web.getParent()).removeView(web);
                }
                web.destroy();
            } catch (Exception ignored) {
                // 个别 ROM 在 WebView 未完全初始化时 destroy 会抛异常
            }
        });
    }

    // ------------------------------------------------------------------
    // JS 桥
    // ------------------------------------------------------------------

    /** 页面回调入口：注意这些都跑在 WebView 的 JS 线程上。 */
    private final class Bridge {

        @JavascriptInterface
        public void onJson(String id, int status, String type, String body) {
            JsonCall call = jsonCalls.get(id);
            if (call == null) {
                return;
            }
            call.status = status;
            call.body = body;
            call.latch.countDown();
        }

        @JavascriptInterface
        public void onMeta(String id, int total, String type) {
            FileCall call = fileCalls.get(id);
            if (call == null) {
                return;
            }
            call.total = total;
            call.contentType = type;
            call.guard.touch();
            if (!CronetHttp.isDownloadableType(type)) {
                call.error = "服务返回了网页而不是文件";
                call.closeQuietly();
                call.latch.countDown();
            }
        }

        @JavascriptInterface
        public void onChunk(String id, String base64) {
            FileCall call = fileCalls.get(id);
            if (call == null || call.error != null) {
                return;
            }
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                call.write(data);
                call.guard.touch();
                call.reportProgress();
            } catch (Throwable e) {
                call.error = "写入文件失败：" + e.getMessage();
                call.closeQuietly();
                call.latch.countDown();
            }
        }

        @JavascriptInterface
        public void onDone(String id) {
            FileCall call = fileCalls.get(id);
            if (call == null) {
                return;
            }
            call.closeQuietly();
            call.latch.countDown();
        }

        @JavascriptInterface
        public void onError(String id, String message) {
            Log.w(TAG, "js error " + id + ": " + message);
            JsonCall json = jsonCalls.get(id);
            if (json != null) {
                json.error = message == null ? "请求失败" : message;
                json.latch.countDown();
                return;
            }
            FileCall file = fileCalls.get(id);
            if (file != null) {
                file.error = message == null ? "下载失败" : message;
                file.closeQuietly();
                file.file.delete();
                file.latch.countDown();
            }
        }
    }

    /** 一次 JSON 请求的等待者。 */
    private static final class JsonCall {
        final CountDownLatch latch = new CountDownLatch(1);
        int status;
        String body;
        String error;
    }

    /** 一次文件下载的等待者：分块写入临时文件。 */
    private static final class FileCall {
        final CountDownLatch latch = new CountDownLatch(1);
        final File file;
        /** 连续无分块看门狗：每收到分块 / 响应头续期一次 */
        final DownloadIdleGuard guard = DownloadIdleGuard.ofSystemClock(CronetHttp.IDLE_TIMEOUT_MS);
        private final LucidaClient.Progress progress;
        long total;
        long written;
        String contentType = "";
        String error;
        private OutputStream out;
        private int lastPercent = -1;

        FileCall(File file, LucidaClient.Progress progress) {
            this.file = file;
            this.progress = progress;
        }

        void write(byte[] data) throws IOException {
            if (out == null) {
                out = new FileOutputStream(file);
            }
            out.write(data);
            written += data.length;
        }

        void reportProgress() {
            if (progress != null && total > 0) {
                int percent = (int) Math.min(100L, written * 100 / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onPercent(percent);
                }
            }
        }

        void closeQuietly() {
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (Exception ignored) {
                    // 关闭失败无需处理
                }
                out = null;
            }
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() > 160 ? one.substring(0, 160) : one;
    }

    /** 从当前主题取色，缺失主题属性时退回给定默认值。 */
    private static int themeColor(View anchor, int attr, int fallback) {
        try {
            return com.google.android.material.color.MaterialColors.getColor(anchor, attr);
        } catch (Exception e) {
            return fallback;
        }
    }
}
