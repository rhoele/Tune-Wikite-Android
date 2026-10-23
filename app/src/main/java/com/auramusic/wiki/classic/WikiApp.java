package com.auramusic.wiki.classic;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

/**
 * 应用入口。
 *
 *   - 启动即按用户选择下发夜间模式（跟随系统 / 浅色 / 深色），F16 的动态取色能力保留；
 *   - 动态取色仅在用户选择「跟随系统壁纸」调色板时生效（Android 12+），
 *     选择固定调色板时由 {@link BaseActivity#onCreate} 的 setTheme 接管，
 *     二者互不干扰，避免出现「选了靛蓝却仍被壁纸色覆盖」的问题。
 */
public class WikiApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppearanceManager.applyDefaultNightMode(this);

        DynamicColorsOptions options = new DynamicColorsOptions.Builder()
                .setPrecondition((activity, theme) -> AppearanceManager.isDynamicPalette(activity))
                .build();
        // 仅作用于 Activity 主题，Service 等非界面组件不受影响
        DynamicColors.applyToActivitiesIfAvailable(this, options);

        // 注入应用上下文，启用封面磁盘缓存（未注入时自动退化为内存 + 网络）
        CoverLoader.init(this);

        NotificationHelper.ensureChannels(this);

        // 持久化保活任务：重启后由系统自动恢复，比 BOOT_COMPLETED 广播可靠
        if (WikiPrefs.backgroundAuditEnabled(this) && WikiPrefs.hasCookie(this)) {
            KeepAliveScheduler.schedule(this);
            // 进程被任何组件拉起（推送 / 闹钟 / 桌面图标）时都确保轮询服务在跑，
            // 避免出现「只有手动开关一次设置里的提醒开关才会推送」的假象
            AuditPollingService.startIfEnabled(this);
        }

        // FCM：从控制台配置就绪后自动取令牌并上报自有服务器；
        // 未放 google-services.json 时会判定为未配置并安静跳过。
        FcmManager.ensureToken(this);
    }
}
