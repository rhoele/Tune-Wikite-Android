package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * 把 FCM 令牌上报到自有服务器。
 *
 * <p>安全与隐私约束：
 * <ul>
 *   <li>只上报令牌、随机设备标识、平台、应用版本与<b>可选的网易云用户 ID</b>，
 *       不上报 Cookie、手机号或任何凭据；</li>
 *   <li>服务地址来自 {@code BuildConfig.FCM_SERVER_BASE_URL}（部署时注入），
 *       仓库中不保存任何密钥；</li>
 *   <li>令牌按「上次成功上报值」去重，避免每次启动都产生一次请求。</li>
 * </ul>
 */
final class FcmTokenUploader {

    private static final String TAG = "WikiFcm";

    private FcmTokenUploader() {
    }

    /** 上报令牌；未配置服务地址或令牌为空时静默跳过 */
    static void upload(Context context, String token) {
        if (context == null || token == null || token.trim().isEmpty()) {
            return;
        }
        final String baseUrl = BuildConfig.FCM_SERVER_BASE_URL == null
                ? "" : BuildConfig.FCM_SERVER_BASE_URL.trim();
        if (baseUrl.isEmpty()) {
            Log.i(TAG, "未配置 FCM_SERVER_BASE_URL，跳过令牌上报");
            return;
        }
        final String normalized = token.trim();
        final long userId = SessionManager.userId(context);
        // 去重键必须包含 userId：登录 / 切换账号后要重新登记，否则服务端仍按匿名设备处理，
        // 表现为「已经登录却收不到针对该账号的审核推送」
        final String fingerprint = normalized + "|" + userId;
        if (fingerprint.equals(WikiPrefs.fcmUploadedToken(context))) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String endpoint = baseUrl.replaceAll("/+$", "") + "/api/fcm/register";

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("token", normalized);
                body.put("platform", "android");
                body.put("deviceId", WikiPrefs.deviceId(app));
                body.put("appVersion", BuildConfig.VERSION_NAME);
                if (userId > 0L) {
                    body.put("userId", userId);
                }
                String response = Http.postJson(endpoint, body.toString(), null, null);
                JSONObject json = new JSONObject(response);
                if (json.optBoolean("ok", true)) {
                    WikiPrefs.setFcmUploadedToken(app, fingerprint);
                    Log.i(TAG, "FCM 令牌上报成功，userId=" + userId);
                } else {
                    Log.w(TAG, "FCM 令牌上报被拒绝：" + json.optString("error", "unknown"));
                }
            } catch (Exception e) {
                // 上报失败不影响推送本身：FCM 令牌仍然有效，下次启动会重试
                Log.w(TAG, "FCM 令牌上报失败：" + e.getMessage());
            }
        }, "fcm-register").start();
    }

    /**
     * 退出登录时解绑当前设备的令牌。
     *
     * <p>联网动作在后台线程完成；无论成功与否都会清掉「已上报」标记，
     * 这样下次登录会重新登记并携带新的 userId。
     */
    static void unregister(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String baseUrl = BuildConfig.FCM_SERVER_BASE_URL == null
                ? "" : BuildConfig.FCM_SERVER_BASE_URL.trim();
        if (baseUrl.isEmpty() || !FcmManager.isConfigured(app)) {
            WikiPrefs.setFcmUploadedToken(app, "");
            return;
        }
        final String endpoint = baseUrl.replaceAll("/+$", "") + "/api/fcm/unregister";
        new Thread(() -> {
            try {
                String token = Tasks.await(
                        com.google.firebase.messaging.FirebaseMessaging
                                .getInstance().getToken(), 10, TimeUnit.SECONDS);
                if (token != null && !token.trim().isEmpty()) {
                    JSONObject body = new JSONObject();
                    body.put("token", token.trim());
                    Http.postJson(endpoint, body.toString(), null, null);
                }
            } catch (Exception e) {
                Log.w(TAG, "FCM 令牌解绑失败：" + e.getMessage());
            } finally {
                WikiPrefs.setFcmUploadedToken(app, "");
            }
        }, "fcm-unregister").start();
    }
}
