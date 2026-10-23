package com.auramusic.wiki.classic;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * FCM 消息接收。
 *
 * <p>覆盖三种进程状态：
 * <ul>
 *   <li><b>前台</b>：始终回调本方法，由客户端自行构建通知（与应用内通知样式一致）；</li>
 *   <li><b>后台 / 进程被杀</b>：服务端使用 data-only 消息，仍会回调本方法；</li>
 *   <li><b>被用户强行停止</b>：系统冻结应用，任何推送都无法送达（平台限制，需在文档中说明）。</li>
 * </ul>
 */
public class FcmMessagingService extends FirebaseMessagingService {

    /** 令牌刷新（首次安装、清除数据、重装、服务端轮换）时回调 */
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        FcmTokenUploader.upload(this, token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);
        Map<String, String> data = new HashMap<>(message.getData());
        RemoteMessage.Notification notification = message.getNotification();
        if (notification != null) {
            // 兼容使用 notification 字段的服务端：data 优先，缺失时回落到 notification
            putIfAbsent(data, "title", notification.getTitle());
            putIfAbsent(data, "body", notification.getBody());
        }
        String messageId = message.getMessageId();
        // 同一消息重复投递时使用相同通知 ID，由系统覆盖而不是堆叠
        int notificationId = messageId == null ? 0 : Math.abs(messageId.hashCode());
        FcmPayload payload = FcmPayload.from(data);
        NotificationHelper.postPush(this, payload, notificationId);

        // 审核结果推送同步落入应用内收件箱：角标与未读卡片无需等待下次轮询
        if (FcmPayload.ROUTE_CONTRIBUTIONS.equals(payload.route)) {
            AuditInbox.addPushEntry(this,
                    firstNonEmpty(data.get("key"), data.get("stableKey"),
                            "push:" + notificationId),
                    firstNonEmpty(data.get("name"), data.get("songName"), payload.title),
                    firstNonEmpty(data.get("artist"), data.get("artistName")),
                    statusOf(data.get("status")),
                    System.currentTimeMillis());
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /** 解析服务端携带的审核状态：支持数字与原 APK 的枚举名。 */
    private static int statusOf(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Integer.MIN_VALUE;
        }
        String value = raw.trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            // 继续按枚举名匹配
        }
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "approved":
            case "pass":
            case "passed":
                return Contribution.STATUS_APPROVED;
            case "partial":
                return Contribution.STATUS_PARTIAL;
            case "rejected":
            case "deny":
            case "denied":
                return Contribution.STATUS_REJECTED;
            case "pending":
                return Contribution.STATUS_PENDING;
            case "auditing":
                return Contribution.STATUS_AUDITING;
            default:
                return Integer.MIN_VALUE;
        }
    }

    private static void putIfAbsent(Map<String, String> data, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String existing = data.get(key);
        if (existing == null || existing.trim().isEmpty()) {
            data.put(key, value);
        }
    }
}
