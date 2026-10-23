package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * FCM 入口。
 *
 * <p>Firebase 采用「条件化接入」：只有 {@code app/google-services.json} 存在时，
 * gradle 才会应用 google-services 插件并注入 Firebase 资源。因此这里必须先判断
 * {@link FirebaseApp} 是否已初始化，未配置时安静跳过，不能抛异常影响启动。
 */
public final class FcmManager {

    private static final String TAG = "WikiFcm";

    private FcmManager() {
    }

    /** Firebase 是否已完成初始化（等价于「是否提供了 google-services.json」） */
    public static boolean isConfigured(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return !FirebaseApp.getApps(context.getApplicationContext()).isEmpty();
        } catch (Throwable t) {
            // 未配置时部分 Firebase 版本会抛 IllegalStateException
            return false;
        }
    }

    /**
     * 获取并上报当前令牌。
     *
     * <p>调用时机：应用启动（{@link WikiApp#onCreate}）与登录态变化后。
     * 令牌刷新由 {@link FcmMessagingService#onNewToken} 单独承接。
     */
    public static void ensureToken(Context context) {
        final Context app = context.getApplicationContext();
        if (!isConfigured(app)) {
            Log.i(TAG, "Firebase 未配置（缺少 google-services.json），跳过令牌获取");
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> FcmTokenUploader.upload(app, token))
                    .addOnFailureListener(e -> Log.w(TAG, "获取 FCM 令牌失败", e));
        } catch (Throwable t) {
            Log.w(TAG, "FCM 初始化异常，已忽略", t);
        }
    }
}
