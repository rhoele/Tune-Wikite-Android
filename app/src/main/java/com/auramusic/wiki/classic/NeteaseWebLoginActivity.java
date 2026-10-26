package com.auramusic.wiki.classic;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云官方网页登录（手机号 + 验证码 / 密码）。
 *
 * <p>直接调用短信 / 密码接口会被网易云风控判定为「当前登录存在安全风险」，
 * 因此这里改为在 WebView 里打开官方移动端登录页，由用户在网易云自己的页面完成登录：
 * <ul>
 *   <li>密码与验证码只提交给网易云，应用侧看不到也不保存；</li>
 *   <li>登录成功后从 Cookie 中取出 {@code MUSIC_U}，与扫码 / Cookie 通道走同一份会话；</li>
 *   <li>轮询间隔 500ms，登录完成即自动返回上一页，不需要用户手动关闭。</li>
 * </ul>
 */
public class NeteaseWebLoginActivity extends BaseActivity {

    /** 登录成功时回传的 Cookie（形如 {@code MUSIC_U=…}） */
    public static final String EXTRA_COOKIE = "extra_cookie";

    private static final String LOGIN_URL = "https://music.163.com/m/login";
    private static final String COOKIE_URL = "https://music.163.com";
    private static final long POLL_MS = 500L;
    /** 移动端 Chrome UA：WebView 默认 UA 容易被风控页拦下 */
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final Pattern MUSIC_U = Pattern.compile("MUSIC_U=([^;\\s]+)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private LinearProgressIndicator progress;
    private boolean captured;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (captured || isFinishing() || isDestroyed()) {
                return;
            }
            String cookie = musicUCookie();
            if (!cookie.isEmpty()) {
                captured = true;
                CookieManager.getInstance().flush();
                Intent data = new Intent();
                data.putExtra(EXTRA_COOKIE, SessionManager.CORE_COOKIE + "=" + cookie);
                setResult(RESULT_OK, data);
                finish();
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_login);
        bindToolbar(R.string.login_web_title);

        web = findViewById(R.id.web_login_view);
        progress = findViewById(R.id.web_login_progress);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(UA);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!captured) {
                    progress.show();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.hide();
            }
        });

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        // WebView 的 Cookie 跨会话持久化：先清掉上次网页登录留下的 MUSIC_U，
        // 否则「退出登录 / 换号」后再进来，轮询会立刻读到旧凭据，把旧账号直接登回来
        if (savedInstanceState == null) {
            cookies.removeAllCookies(cleared -> {
                cookies.flush();
                if (web != null && !isFinishing() && !isDestroyed()) {
                    web.loadUrl(LOGIN_URL);
                }
            });
        } else {
            web.loadUrl(LOGIN_URL);
        }
        handler.postDelayed(poll, 800L);
    }

    /** 读取 music.163.com 域下的 MUSIC_U；不存在返回空串。 */
    private String musicUCookie() {
        CookieManager cookies = CookieManager.getInstance();
        String header = cookies.getCookie(COOKIE_URL);
        if (TextUtils.isEmpty(header)) {
            header = cookies.getCookie(LOGIN_URL);
        }
        if (TextUtils.isEmpty(header)) {
            return "";
        }
        Matcher matcher = MUSIC_U.matcher(header);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
