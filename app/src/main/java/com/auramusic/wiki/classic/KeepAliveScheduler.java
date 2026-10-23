package com.auramusic.wiki.classic;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * 持久化保活任务调度器。
 *
 * <p><b>为什么它是开机恢复的主力</b>：{@code BOOT_COMPLETED} 广播在 Android 8+ 会被系统
 * 延迟投递，部分厂商 ROM（实测 ColorOS）甚至完全不投递，导致重启后闹钟没续订、服务没恢复。
 * 而 {@code setPersisted(true)} 的 Job 由系统持久化保存，<b>重启后由系统自动重新调度</b>，
 * 不依赖任何广播，是普通应用能拿到的最可靠的开机恢复手段。
 *
 * <p>周期取系统允许的最小值 15 分钟；每次执行只做「确保服务在跑 + 续订闹钟」，开销极小。
 */
final class KeepAliveScheduler {

    private static final String TAG = "KeepAliveJob";

    /** 固定 JobId，便于判重与取消 */
    private static final int JOB_ID = 7301;

    /** JobScheduler 允许的最小周期（15 分钟） */
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    private KeepAliveScheduler() {
    }

    /** 排定（幂等）：已存在待执行任务时不重复提交。 */
    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && scheduler.getPendingJob(JOB_ID) != null) {
                return;
            }
            ComponentName component = new ComponentName(context, KeepAliveJobService.class);
            JobInfo job = new JobInfo.Builder(JOB_ID, component)
                    // 重启后由系统自动恢复，这是开机保活的关键
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            scheduler.schedule(job);
            Log.d(TAG, "保活任务已排定，周期=" + (PERIOD_MS / 60000) + " 分钟");
        } catch (Exception e) {
            // 部分 ROM 限制持久化任务，失败时仍有闹钟 + 前台服务两层兜底
            Log.w(TAG, "排定保活任务失败", e);
        }
    }

    /** 取消保活任务（用户关闭后台提醒 / 退出登录时调用）。 */
    static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        try {
            scheduler.cancel(JOB_ID);
            Log.d(TAG, "已取消保活任务");
        } catch (Exception e) {
            Log.w(TAG, "取消保活任务失败", e);
        }
    }
}
