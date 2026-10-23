package com.auramusic.wiki.classic;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * FCM 消息载荷的纯逻辑解析。
 *
 * <p>服务端约定使用 <b>data-only</b> 消息（不依赖 notification 字段），这样前台、后台、
 * 进程被杀三种状态下都会回调 {@code onMessageReceived}，通知样式与点击行为完全由客户端掌控。
 *
 * <p>字段约定：
 * <ul>
 *   <li>{@code title} / {@code body}：通知标题与正文（缺失时用应用名与兜底文案）；</li>
 *   <li>{@code route}：点击后的去向，{@code wiki}（歌曲百科）或 {@code contributions}（我的贡献，默认）；</li>
 *   <li>{@code query} 或 {@code songId}：{@code route=wiki} 时的查询词；</li>
 *   <li>{@code group}：通知分组键（可选），同组通知会在通知栏折叠。</li>
 * </ul>
 */
final class FcmPayload {

    static final String ROUTE_WIKI = "wiki";
    static final String ROUTE_CONTRIBUTIONS = "contributions";

    final String title;
    final String body;
    final String route;
    @Nullable
    final String query;
    @Nullable
    final String group;

    private FcmPayload(String title, String body, String route,
                       @Nullable String query, @Nullable String group) {
        this.title = title;
        this.body = body;
        this.route = route;
        this.query = query;
        this.group = group;
    }

    static FcmPayload from(@Nullable Map<String, String> data) {
        Map<String, String> source = data == null ? Collections.<String, String>emptyMap() : data;
        String title = trimToNull(source.get("title"));
        if (title == null) {
            title = "曲库共建";
        }
        String body = trimToNull(source.get("body"));
        if (body == null) {
            body = trimToNull(source.get("message"));
        }
        if (body == null) {
            body = "";
        }
        String route = normalizeRoute(source.get("route"));
        String query = firstNonEmpty(source.get("query"), source.get("songId"));
        if (ROUTE_WIKI.equals(route) && query == null) {
            // 没有具体查询词时不要跳到空的百科页，退回「我的贡献」更有意义
            route = ROUTE_CONTRIBUTIONS;
        }
        return new FcmPayload(title, body, route, query, trimToNull(source.get("group")));
    }

    /** 未知 / 空值统一落到「我的贡献」，避免点击通知后无页面可去 */
    static String normalizeRoute(@Nullable String raw) {
        if (raw == null) {
            return ROUTE_CONTRIBUTIONS;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (ROUTE_WIKI.equals(value) || "song".equals(value) || "query".equals(value)) {
            return ROUTE_WIKI;
        }
        return ROUTE_CONTRIBUTIONS;
    }

    private static String firstNonEmpty(String a, String b) {
        String v = trimToNull(a);
        return v != null ? v : trimToNull(b);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
