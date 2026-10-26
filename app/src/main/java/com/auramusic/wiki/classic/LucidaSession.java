package com.auramusic.wiki.classic;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * lucida.to 的 Cloudflare 会话准备器。
 *
 * <p>lucida 现在对非浏览器客户端下发 `cf-mitigated: challenge`，普通 HTTP 请求一律 403。
 * 实测「真实浏览器过验证拿 cf_clearance + 同一 UA + 带 Sec-Fetch 头」后，普通 HTTP 客户端
 * 即可正常调用接口与下载文件，因此这里只负责：
 * <ol>
 *   <li>用 WebView 加载 lucida.to，让 Cloudflare 托管挑战自动通过；</li>
 *   <li>把 {@code cf_clearance} 与 WebView 的 UA 交给 {@link LucidaClient}；</li>
 *   <li>WebView 随即销毁，不常驻。</li>
 * </ol>
 *
 * <p>验证期间显示一个整屏 WebView（挑战偶尔需要交互）；成功或超时后自动关闭。
 */
final class LucidaSession {

    private static final String HOME = "https://lucida.to/";
    private static final long POLL_MS = 400L;
    private static final Object LOCK = new Object();

    private LucidaSession() {
    }

    /**
     * 确保存在可用的 lucida 会话。
     *
     * <p>会阻塞调用线程（下载线程），内部切主线程创建 WebView。
     *
     * @return true 表示已拿到 cf_clearance
     */
    static boolean ensure(Activity activity, long timeoutMs) {
        if (LucidaClient.hasSession()) {
            return true;
        }
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        synchronized (LOCK) {
            if (LucidaClient.hasSession()) {
                return true;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean success = new AtomicBoolean(false);
            activity.runOnUiThread(() -> show(activity, timeoutMs, latch, success));
            try {
                latch.await(timeoutMs + 8000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success.get() && LucidaClient.hasSession();
        }
    }

    private static void show(final Activity activity, final long timeoutMs,
                             final CountDownLatch latch, final AtomicBoolean success) {
        // WebView 挂到 Activity 内容容器的「最底层」：上面的业务界面是不透明的，
        // 把验证页完全盖住——既不闪白，也不会像弹窗那样把整屏压黑。
        // 注意不能用 INVISIBLE / alpha=0：Chromium 会把页面当后台标签限流定时器，
        // Cloudflare 的托管挑战可能跑不完。
        final ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            latch.countDown();
            return;
        }

        final WebView web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // UA 在过验证前锁定：后续 HTTP 请求必须发送完全相同的 UA
        final String userAgent = WebSettings.getDefaultUserAgent(activity);
        settings.setUserAgentString(userAgent);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.TRANSPARENT);
        content.addView(web, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final float density = activity.getResources().getDisplayMetrics().density;
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

        // 只弹一张小卡片，不压暗、不铺满；点卡片才把验证页翻到最前面
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCancelable(false);
        card.setOnClickListener(v -> {
            card.setVisibility(android.view.View.GONE);
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

        final Handler handler = new Handler(Looper.getMainLooper());

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        // 清掉上一次可能已失效的通行证，避免误判为「已验证」
        cookies.setCookie(HOME, "cf_clearance=; Path=/; Domain=.lucida.to; Max-Age=0");

        // 轮询等待 cf_clearance，超时即放弃并关闭 WebView
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                String value = CookieManager.getInstance().getCookie(HOME);
                if (value != null && value.contains("cf_clearance")) {
                    CookieManager.getInstance().flush();
                    LucidaClient.setSession(value, userAgent);
                    success.set(true);
                    finish(handler, this, dialog, web, latch);
                    return;
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    finish(handler, this, dialog, web, latch);
                    return;
                }
                handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(tick, 800L);
        web.loadUrl(HOME);
    }

    /** 从当前主题取色，缺失主题属性时退回给定默认值。 */
    private static int themeColor(View anchor, int attr, int fallback) {
        try {
            return com.google.android.material.color.MaterialColors.getColor(anchor, attr);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void finish(Handler handler, Runnable poll, Dialog dialog,
                               WebView web, CountDownLatch latch) {
        handler.removeCallbacks(poll);
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
            // 页面已销毁时忽略
        }
        try {
            if (web.getParent() instanceof ViewGroup) {
                ((ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
        } catch (Exception ignored) {
            // 个别 ROM 在 WebView 未完全初始化时 destroy 会抛异常
        }
        latch.countDown();
    }
}
