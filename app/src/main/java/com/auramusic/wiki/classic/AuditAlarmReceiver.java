package com.auramusic.wiki.classic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 离线审核提醒的闹钟调度器（需求 3）。
 *
 * <p><b>为什么需要它</b>：{@link AuditPollingService} 是前台服务，只在应用进程存活、
 * 且系统未回收服务时才轮询。一旦进程被系统杀掉、或用户在最近任务里划掉应用
 * （非「强行停止」），服务即消失，此时<b>收不到任何通知</b>。
 *
 * <p>本接收器通过 {@link AlarmManager} 注册一个「一次性的、允许在 Doze 下唤醒」的闹钟，
 * 触发后立即续订下一次，并在后台线程完成一轮检查：
 * <ul>
 *   <li>进程已退出 → 系统重新拉起本应用的进程来投递广播，检查照常执行；</li>
 *   <li>设备重启 / 应用被覆盖安装 → 由 {@code BOOT_COMPLETED} / {@code MY_PACKAGE_REPLACED} 续订；</li>
 *   <li>处于 Doze / 空闲 → 使用 {@code setAndAllowWhileIdle}，仍保证被唤醒（时点可能略有偏移）。</li>
 * </ul>
 *
 * <p><b>能力边界</b>：用户「强行停止」（设置里强制停止 / 部分 ROM 的划掉清理）后，
 * Android 会冻结该应用的全部闹钟与广播，直到用户再次手动打开应用——这是系统级限制，
 * 任何本地方案都无法绕过；真正不受此限制的只有服务端推送（FCM / 厂商通道）。
 */
public class AuditAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AuditAlarm";

    /** 自定义闹钟触发动作 */
    static final String ACTION_TICK = "com.auramusic.wiki.classic.action.AUDIT_TICK";

    private static final int REQUEST_CODE = 4211;

    /** 续订下一次离线检查（未开启后台提醒 / 无凭据时等价于取消） */
    static void schedule(Context context) {
        if (!WikiPrefs.backgroundAuditEnabled(context) || !WikiPrefs.hasCookie(context)) {
            cancel(context);
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long intervalMs = WikiPrefs.pollingIntervalMinutes(context) * 60 * 1000L;
        long triggerAt = System.currentTimeMillis() + intervalMs;
        try {
            // 用 inexact 的 allowWhileIdle：无需 SCHEDULE_EXACT_ALARM 权限，
            // 且能在 Doze 下被唤醒，对本场景（分钟级提醒）足够。
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context));
            Log.d(TAG, "已排定下一次离线检查，间隔=" + (intervalMs / 60000) + " 分钟");
        } catch (Exception e) {
            Log.w(TAG, "排定离线检查失败", e);
        }
    }

    /** 取消已排定的离线检查（关闭后台提醒 / 退出登录 / 凭据失效时调用） */
    static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        try {
            am.cancel(pendingIntent(context));
            Log.d(TAG, "已取消离线检查闹钟");
        } catch (Exception e) {
            Log.w(TAG, "取消离线检查闹钟失败", e);
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, AuditAlarmReceiver.class);
        intent.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Context app = context.getApplicationContext();

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 开机 / 覆盖安装后系统会清掉闹钟，此处续订
            schedule(app);
            // 持久化 Job 正常情况下由系统自动恢复，这里再排一次做兜底（幂等）
            KeepAliveScheduler.schedule(app);
            return;
        }
        // 本接收器为 exported=true（BOOT_COMPLETED 需要），故只认自家闹钟动作，
        // 其余外部广播（即使以显式 Intent 投递）一律忽略，避免被第三方触发无谓的网络检查。
        if (!ACTION_TICK.equals(action)) {
            Log.d(TAG, "忽略非闹钟广播 action=" + action);
            return;
        }

        // 闹钟触发：先把下一次排上，再执行本轮检查，避免检查耗时导致漏排。
        schedule(app);

        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                AuditPollingService.runOnce(app);
            } catch (Throwable e) {
                // 后台检查的任何异常都不得让进程崩溃
                Log.w(TAG, "离线检查执行失败", e);
            } finally {
                try {
                    pending.finish();
                } catch (Exception ignored) {
                }
            }
        }, "wiki-audit-alarm").start();
    }
}
