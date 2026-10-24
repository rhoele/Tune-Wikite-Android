package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * FCM data 载荷解析契约（点击落点 / 兜底文案）。
 *
 * <p>这些字段由服务端约定，客户端必须对缺失、大小写、未知路由做稳定兜底，
 * 否则会出现「点击通知没有任何反应」或跳到空白页。
 */
public class FcmPayloadTest {

    @Test
    public void emptyPayloadFallsBackToAppNameAndContributions() {
        FcmPayload payload = FcmPayload.from(null);
        assertEquals("Tune Wikite", payload.title);
        assertEquals("", payload.body);
        assertEquals(FcmPayload.ROUTE_CONTRIBUTIONS, payload.route);
        assertNull(payload.query);
        assertNull(payload.group);
    }

    @Test
    public void usesTitleAndBodyFromData() {
        Map<String, String> data = new HashMap<>();
        data.put("title", "  审核通过  ");
        data.put("body", " 你的歌词已通过审核 ");
        FcmPayload payload = FcmPayload.from(data);
        assertEquals("审核通过", payload.title);
        assertEquals("你的歌词已通过审核", payload.body);
    }

    @Test
    public void bodyFallsBackToMessageField() {
        Map<String, String> data = new HashMap<>();
        data.put("message", "兼容旧字段");
        assertEquals("兼容旧字段", FcmPayload.from(data).body);
    }

    @Test
    public void routeNormalizationIsCaseInsensitive() {
        assertEquals(FcmPayload.ROUTE_WIKI, FcmPayload.normalizeRoute("WIKI"));
        assertEquals(FcmPayload.ROUTE_WIKI, FcmPayload.normalizeRoute("song"));
        assertEquals(FcmPayload.ROUTE_WIKI, FcmPayload.normalizeRoute("Query"));
        assertEquals(FcmPayload.ROUTE_CONTRIBUTIONS, FcmPayload.normalizeRoute("unknown"));
        assertEquals(FcmPayload.ROUTE_CONTRIBUTIONS, FcmPayload.normalizeRoute(null));
    }

    @Test
    public void wikiRouteCarriesSongIdAsQuery() {
        Map<String, String> data = new HashMap<>();
        data.put("route", "wiki");
        data.put("songId", "3365925110");
        FcmPayload payload = FcmPayload.from(data);
        assertEquals(FcmPayload.ROUTE_WIKI, payload.route);
        assertEquals("3365925110", payload.query);
    }

    @Test
    public void wikiRouteWithoutQueryFallsBackToContributions() {
        Map<String, String> data = new HashMap<>();
        data.put("route", "wiki");
        FcmPayload payload = FcmPayload.from(data);
        assertEquals(FcmPayload.ROUTE_CONTRIBUTIONS, payload.route);
        assertNull(payload.query);
    }

    @Test
    public void groupIsTrimmedAndOptional() {
        Map<String, String> data = new HashMap<>();
        data.put("group", "  audit  ");
        assertEquals("audit", FcmPayload.from(data).group);

        data.put("group", "   ");
        assertNull(FcmPayload.from(data).group);
    }
}
