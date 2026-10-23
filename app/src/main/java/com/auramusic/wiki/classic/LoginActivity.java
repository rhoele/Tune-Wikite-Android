package com.auramusic.wiki.classic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 登录页（需求 2：还原完整登录流程）。
 *
 * 通道一 · Web 端二维码扫码：
 *   unikey 取 key → 绘制二维码 → 每 2 秒轮询 client/login
 *   （801 待扫码 / 802 已扫码 / 803 确认 / 800 过期），
 *   803 时从响应头 Set-Cookie 与响应体 cookie 字段双重提取会话。
 * 通道二 · Cookie 登录：
 *   先做本地格式校验（必须含 MUSIC_U 且取值合理），再请求账号资料接口验证会话有效性。
 *
 * 两条通道最终都写入 {@link SessionManager}，与「我的贡献」「后台审核提醒」共享同一份凭据。
 * 全流程带代际校验与在途取消，页面销毁即停止轮询，避免回调打到已销毁的界面。
 */
public class LoginActivity extends BaseActivity {

    public static final String EXTRA_START_TAB = "extra_start_tab";
    public static final int TAB_QR = 0;
    public static final int TAB_COOKIE = 1;

    /** 扫码状态轮询间隔：与网易云 Web 端一致的 2 秒 */
    private static final long QR_POLL_INTERVAL_MS = 2000L;
    /** 二维码绘制边长（dp） */
    private static final int QR_SIZE_DP = 208;

    private TabLayout tabs;
    private View pageQr;
    private View pageCookie;
    private ImageView ivQr;
    private TextView tvQrStatus;
    private MaterialButton btnQrRefresh;
    private TextInputLayout tilCookie;
    private EditText etCookie;
    private View busy;
    private TextView tvBusyText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Http.CancelToken inflight;
    private int generation;
    private String qrKey;
    /** 轮询过程中累积的 Cookie（NMTID 等），每次轮询需带回 */
    private String qrJar = "";
    private boolean polling;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        bindToolbar(R.string.login_title);

        tabs = findViewById(R.id.login_tabs);
        pageQr = findViewById(R.id.page_qr);
        pageCookie = findViewById(R.id.page_cookie);
        ivQr = findViewById(R.id.iv_qr);
        tvQrStatus = findViewById(R.id.tv_qr_status);
        btnQrRefresh = findViewById(R.id.btn_qr_refresh);
        tilCookie = findViewById(R.id.til_cookie);
        etCookie = findViewById(R.id.et_cookie);
        InputScroll.enable(etCookie);
        // 预填当前已保存的凭据，避免用户再次手动粘贴 / 出现「明明有 Cookie 却提示为空」
        String savedCookie = SessionManager.cookie(this);
        if (!savedCookie.isEmpty()) {
            etCookie.setText(savedCookie);
        }
        busy = findViewById(R.id.login_busy);
        tvBusyText = findViewById(R.id.tv_busy_text);

        tabs.addTab(tabs.newTab().setText(R.string.login_tab_qr));
        tabs.addTab(tabs.newTab().setText(R.string.login_tab_cookie));
        int startTab = getIntent() == null
                ? TAB_QR : getIntent().getIntExtra(EXTRA_START_TAB, TAB_QR);
        selectTab(startTab);

        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                HapticsManager.toggle(tabs);
                boolean qr = tab.getPosition() == TAB_QR;
                pageQr.setVisibility(qr ? View.VISIBLE : View.GONE);
                pageCookie.setVisibility(qr ? View.GONE : View.VISIBLE);
                if (qr) {
                    startQrFlow();
                } else {
                    stopPolling();
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (tab.getPosition() == TAB_QR) {
                    startQrFlow();
                }
            }
        });

        btnQrRefresh.setOnClickListener(v -> {
            HapticsManager.swipe(v);
            refreshQr();
        });

        findViewById(R.id.btn_paste).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pasteFromClipboard();
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            HapticsManager.tap(v);
            etCookie.setText("");
            tilCookie.setError(null);
            Feedback.show(this, R.string.settings_cookie_cleared);
        });
        findViewById(R.id.btn_cookie_login).setOnClickListener(v -> {
            HapticsManager.tap(v);
            submitCookie();
        });

        if (startTab == TAB_QR) {
            startQrFlow();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 回到前台且仍停留在扫码页时恢复轮询；离开时停止，避免后台每 2 秒空跑
        if (tabs != null && tabs.getSelectedTabPosition() == TAB_QR && qrKey != null) {
            startQrFlow();
        }
    }

    @Override
    protected void onStop() {
        stopPolling();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // 代际自增 + 断开在途连接 + 清空延时任务，确保销毁后不再有任何回调
        generation++;
        stopPolling();
        cancelInflight();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void selectTab(int tab) {
        TabLayout.Tab t = tabs.getTabAt(tab < 0 || tab > 1 ? TAB_QR : tab);
        if (t != null) {
            t.select();
        }
    }

    // ------------------------------------------------------------------
    // 通道一：二维码
    // ------------------------------------------------------------------

    private void refreshQr() {
        stopPolling();
        qrKey = null;
        qrJar = "";
        ivQr.setImageResource(R.drawable.ic_login_qr_muted);
        startQrFlow();
    }

    /** 申请二维码会话并绘制，成功后进入轮询。 */
    private void startQrFlow() {
        if (polling) {
            return;
        }
        if (qrKey != null) {
            // 已有会话（例如切换标签页后返回），直接恢复轮询，不重复申请
            polling = true;
            schedulePoll();
            return;
        }
        cancelInflight();
        final int gen = ++generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;

        tvQrStatus.setText(R.string.login_qr_loading_key);
        btnQrRefresh.setEnabled(false);

        new Thread(() -> {
            try {
                NetEaseAuthClient.QrSession session = NetEaseAuthClient.createQrSession(token);
                int size = (int) (QR_SIZE_DP * getResources().getDisplayMetrics().density);
                final Bitmap bitmap = QrCodeRenderer.render(session.content, size);
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    qrKey = session.key;
                    qrJar = session.cookies;
                    if (bitmap != null) {
                        ivQr.setImageBitmap(bitmap);
                    } else {
                        ivQr.setImageResource(R.drawable.ic_login_qr_muted);
                    }
                    tvQrStatus.setText(R.string.login_qr_waiting);
                    btnQrRefresh.setEnabled(true);
                    polling = true;
                    schedulePoll();
                });
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    return;
                }
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    tvQrStatus.setText(R.string.login_qr_failed);
                    btnQrRefresh.setEnabled(true);
                    Feedback.showLong(LoginActivity.this, errorText(e));
                });
            }
        }, "wiki-qr-create").start();
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void schedulePoll() {
        handler.postDelayed(this::pollOnce, QR_POLL_INTERVAL_MS);
    }

    private void pollOnce() {
        if (!polling || qrKey == null || isFinishing() || isDestroyed()) {
            return;
        }
        cancelInflight();
        final int gen = generation;
        final String key = qrKey;
        final String jar = qrJar;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;

        new Thread(() -> {
            try {
                final NetEaseAuthClient.QrPollResult result =
                        NetEaseAuthClient.pollQr(key, jar, token);
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    qrJar = result.jar;
                    handleQrState(result);
                });
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    return;
                }
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    // 单次网络抖动不中断流程：提示后按同一节奏重试
                    tvQrStatus.setText(errorText(e));
                    if (polling) {
                        schedulePoll();
                    }
                });
            }
        }, "wiki-qr-poll").start();
    }

    private void handleQrState(NetEaseAuthClient.QrPollResult result) {
        switch (result.state) {
            case CONFIRMED:
                polling = false;
                applyQrCookie(result.cookie);
                break;
            case SCANNED:
                tvQrStatus.setText(R.string.login_qr_scanned);
                if (polling) {
                    schedulePoll();
                }
                break;
            case EXPIRED:
                polling = false;
                qrKey = null;
                tvQrStatus.setText(R.string.login_qr_expired);
                btnQrRefresh.setEnabled(true);
                break;
            case WAITING:
            default:
                tvQrStatus.setText(R.string.login_qr_waiting);
                if (polling) {
                    schedulePoll();
                }
                break;
        }
    }

    /** 扫码确认后落库：拉取账号资料做最终校验，失败时降级为「仅保存 Cookie」。 */
    private void applyQrCookie(final String cookie) {
        if (!SessionManager.looksValid(cookie)) {
            tvQrStatus.setText(R.string.login_err_invalid);
            btnQrRefresh.setEnabled(true);
            return;
        }
        showBusy(R.string.login_verifying);
        final int gen = generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;

        new Thread(() -> {
            NetEaseAuthClient.Profile profile = null;
            try {
                profile = NetEaseAuthClient.fetchProfile(cookie, token);
            } catch (WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    return;
                }
                // 扫码已下发 MUSIC_U，资料接口偶发失败不阻断登录
            }
            final NetEaseAuthClient.Profile result = profile;
            runOnUiThread(() -> {
                if (gen != generation || isFinishing() || isDestroyed()) {
                    return;
                }
                inflight = null;
                SessionManager.save(LoginActivity.this, cookie, result, SessionManager.TYPE_QR);
                finishWithSuccess();
            });
        }, "wiki-qr-profile").start();
    }

    // ------------------------------------------------------------------
    // 通道二：Cookie 登录
    // ------------------------------------------------------------------

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Feedback.show(this, R.string.login_clip_empty);
                return;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (TextUtils.isEmpty(text)) {
                Feedback.show(this, R.string.login_clip_empty);
                return;
            }
            etCookie.setText(SessionManager.sanitizeCookie(text.toString()));
            tilCookie.setError(null);
            Feedback.show(this, R.string.login_pasted);
        } catch (Exception e) {
            Feedback.show(this, R.string.login_clip_empty);
        }
    }

    private void submitCookie() {
        String raw = etCookie.getText() == null ? "" : etCookie.getText().toString();
        String reason = SessionManager.validateReason(raw);
        if (reason != null) {
            HapticsManager.reject(etCookie);
            tilCookie.setError(cookieErrorText(reason));
            etCookie.requestFocus();
            return;
        }
        tilCookie.setError(null);

        final String cookie = SessionManager.sanitizeCookie(raw);
        showBusy(R.string.login_verifying);
        cancelInflight();
        final int gen = ++generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;

        new Thread(() -> {
            try {
                final NetEaseAuthClient.Profile profile =
                        NetEaseAuthClient.fetchProfile(cookie, token);
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    SessionManager.save(LoginActivity.this, cookie, profile, SessionManager.TYPE_COOKIE);
                    finishWithSuccess();
                });
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    return;
                }
                runOnUiThread(() -> {
                    if (gen != generation || isFinishing() || isDestroyed()) {
                        return;
                    }
                    inflight = null;
                    hideBusy();
                    HapticsManager.reject(etCookie);
                    String msg = errorText(e);
                    tilCookie.setError(msg);
                    Feedback.show(LoginActivity.this, msg);
                });
            }
        }, "wiki-cookie-login").start();
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private void finishWithSuccess() {
        HapticsManager.confirm(findViewById(android.R.id.content));
        // 这里紧接着就 finish()，Snackbar 会随窗口立即消失，只能继续用 Toast
        Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void cancelInflight() {
        if (inflight != null) {
            inflight.cancel();
            inflight = null;
        }
    }

    private void showBusy(int textRes) {
        tvBusyText.setText(textRes);
        busy.setVisibility(View.VISIBLE);
    }

    private void hideBusy() {
        busy.setVisibility(View.GONE);
    }

    /** 输入校验失败文案 */
    private String cookieErrorText(String reason) {
        switch (reason) {
            case "empty":
                return getString(R.string.login_err_empty);
            case "missing_core":
                return getString(R.string.login_err_missing_core);
            case "malformed":
                return getString(R.string.login_err_malformed);
            default:
                return getString(R.string.login_err_invalid);
        }
    }

    /** 网络/业务异常文案（按异常分级差异化提示） */
    private String errorText(WikiException e) {
        switch (e.getKind()) {
            case TIMEOUT:
                return getString(R.string.login_err_timeout);
            case NO_NETWORK:
                return getString(R.string.login_err_network);
            case NO_CREDENTIAL:
                // 优先展示具体原因（如「扫码成功但未获取到登录凭据」），避免笼统提示
                return TextUtils.isEmpty(e.getMessage())
                        ? getString(R.string.login_err_invalid) : e.getMessage();
            case HTTP:
                return getString(R.string.wiki_http_error, e.getHttpCode());
            default:
                return getString(R.string.login_err_unknown);
        }
    }
}
