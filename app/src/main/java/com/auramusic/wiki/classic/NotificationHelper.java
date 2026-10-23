package com.auramusic.wiki.classic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知能力集中封装（C4 / C5）：
 * 渠道创建、审核结果推送、测试通知与权限判定。
 * 抽出后 SettingsActivity 与 AuditPollingService 共用同一套渠道配置，避免渠道参数不一致。
 */
public final class NotificationHelper {

    private static final int TEST_NOTIFICATION_ID = 1001;

    /** FCM 推送渠道：与 AndroidManifest 中 default_notification_channel_id 保持一致 */
    public static final String CHANNEL_PUSH = "fcm_push";

    /**
     * 需求 5：通知 ID 自增序列。
     * 旧实现用 {@code System.currentTimeMillis() % 100000} 作 ID，
     * 同一轮轮询内多条结果在同一毫秒内生成时会拿到相同 ID 而互相覆盖，
     * 导致部分通知被静默吞掉。改用进程内单调自增，天然避免碰撞。
     * 起始值取当前秒数低位，尽量拉开与测试通知/前台通知的 ID 区间。
     */
    private static final AtomicInteger AUDIT_NOTIFICATION_SEQ =
            new AtomicInteger((int) (System.currentTimeMillis() / 1000L % 9000) + 2000);

    private NotificationHelper() {
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel audit = new NotificationChannel(
                AuditPollingService.CHANNEL_AUDIT,
                "曲库审核结果通知",
                NotificationManager.IMPORTANCE_HIGH);
        audit.setDescription("当歌曲、滚动歌词或翻译有任何审核变动时发送通知");
        nm.createNotificationChannel(audit);

        // 前台服务常驻通知：必须使用系统允许的最低层级，避免打扰用户。
        // 渠道创建后重要性不可修改，因此从 wiki_fg_service 升版到 _v2，
        // 并删除旧渠道，保证老用户也能真正降到 IMPORTANCE_MIN。
        NotificationChannel fg = new NotificationChannel(
                AuditPollingService.CHANNEL_FOREGROUND,
                "曲库后台监听服务",
                NotificationManager.IMPORTANCE_MIN);
        fg.setDescription("保持后台审核状态监听所需的常驻通知");
        fg.setShowBadge(false);
        fg.setSound(null, null);
        fg.enableVibration(false);
        fg.enableLights(false);
        fg.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(fg);
        nm.deleteNotificationChannel("wiki_fg_service");

        NotificationChannel push = new NotificationChannel(
                CHANNEL_PUSH,
                "推送消息",
                NotificationManager.IMPORTANCE_HIGH);
        push.setDescription("服务端通过 Firebase Cloud Messaging 下发的通知");
        nm.createNotificationChannel(push);
    }

    public static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 发送一次系统通知测试（C5） */
    public static void sendAuditChannelTest(Context context) {
        // 未授权时通知会被系统静默丢弃，之前仍提示「已发送」会误导用户
        if (!notificationsAllowed(context)) {
            throw new IllegalStateException("通知权限未授予，请在系统设置中允许通知");
        }
        ensureChannels(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            throw new IllegalStateException("通知服务不可用");
        }
        PendingIntent pi = contentIntent(context, 100);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, AuditPollingService.CHANNEL_AUDIT)
                .setSmallIcon(R.drawable.ic_fact_check)
                .setContentTitle("曲库共建 · 审核结果通知测试")
                .setContentText("系统通知通道测试成功！后续有审核变动时将自动发送提醒。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(TEST_NOTIFICATION_ID, builder.build());
    }

    /** 推送一条审核结果通知 */
    public static void postAuditResult(Context context, String title, Contribution.Status status) {
        if (!notificationsAllowed(context)) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannels(context);

        String prefix;
        switch (status) {
            case APPROVED:
                prefix = "【审核通过】";
                break;
            case PARTIAL:
                prefix = "【部分通过】";
                break;
            case REJECTED:
                prefix = "【未采纳】";
                break;
            default:
                prefix = "【审核更新】";
                break;
        }

        int notificationId = AUDIT_NOTIFICATION_SEQ.incrementAndGet();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, AuditPollingService.CHANNEL_AUDIT)
                .setSmallIcon(R.drawable.ic_fact_check)
                .setContentTitle(prefix + " " + title)
                .setContentText("你在网易云曲库提交的资料已有审核反馈，点击查看详情。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context, notificationId));

        try {
            nm.notify(notificationId, builder.build());
        } catch (Exception ignored) {
            // 通知被系统限流时忽略，不影响主流程
        }
    }

    /** 点击通知回到「我的贡献」目的地 */
    private static PendingIntent contentIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DEST_CONTRIBUTIONS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * 展示一条 FCM 推送。
     *
     * <p>通知 ID 由消息 ID 派生，重复投递会覆盖而不是堆叠；点击后按 {@code route}
     * 跳到「歌曲百科」（可带查询词）或「我的贡献」。
     */
    public static void postPush(Context context, FcmPayload payload, int notificationId) {
        if (payload == null || !notificationsAllowed(context)) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        ensureChannels(context);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, CHANNEL_PUSH)
                .setSmallIcon(R.drawable.ic_mark_email_unread)
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pushIntent(context, payload, notificationId));
        if (!payload.body.isEmpty()) {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(payload.body));
        }
        if (payload.group != null) {
            // 同组通知在通知栏折叠，避免批量推送刷屏
            builder.setGroup(payload.group);
        }
        try {
            nm.notify(notificationId, builder.build());
        } catch (Exception ignored) {
            // 通知被系统限流时忽略，推送内容仍可由服务端重试
        }
    }

    /** 推送点击：按 route 决定落点，wiki 路由可携带查询词直接发起查询 */
    private static PendingIntent pushIntent(Context context, FcmPayload payload, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        boolean wiki = FcmPayload.ROUTE_WIKI.equals(payload.route);
        intent.putExtra(MainActivity.EXTRA_DESTINATION,
                wiki ? MainActivity.DEST_WIKI : MainActivity.DEST_CONTRIBUTIONS);
        if (wiki && payload.query != null) {
            intent.putExtra(MainActivity.EXTRA_SONG_QUERY, payload.query);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
