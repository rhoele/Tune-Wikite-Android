package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「全部」筛选的通道载荷修正单测（需求 1）。
 *
 * <p>实测结论（见 {@code .diag/all-types-check.js}）：
 * <ul>
 *   <li>网页通道（WEAPI）传 {@code type=0} 返回 {@code total=0} 的空列表，
 *       省略 {@code type} 才是「全部」；</li>
 *   <li>省略 {@code type} 在 App 通道（EAPI）会回落到「补充专辑」，
 *       这正是「『全部』只显示补充专辑」的根因，故 EAPI 必须保留 {@code type=0}。</li>
 * </ul>
 *
 * <p>本测试固化 {@link NetEaseWikiClient#webPayload} 的「只剔 {@code type=0}、
 * 其余原样透传」契约，防止回归。
 */
public class NetEaseWikiClientPayloadTest {

    private static JSONObject payloadWithType(int type) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", type);
        p.put("limit", 20);
        p.put("offset", 0);
        p.put("order", "desc");
        p.put("sortBy", "createTime");
        return p;
    }

    /** 网页通道「全部」：必须剔除 type，其余字段保持不变 */
    @Test
    public void webPayloadDropsTypeForTypeAll() throws Exception {
        JSONObject original = payloadWithType(NetEaseWikiClient.TYPE_ALL);
        JSONObject adapted = NetEaseWikiClient.webPayload(original);

        assertFalse("type=0 在网页通道必须被剔除", adapted.has("type"));
        assertEquals(20, adapted.optInt("limit"));
        assertEquals("desc", adapted.optString("order"));
        assertEquals("createTime", adapted.optString("sortBy"));
        // 原始载荷不得被就地修改（fetchPage 的 payload 会被复用）
        assertTrue("不得就地修改原载荷", original.has("type"));
        assertNotSame("应返回副本", original, adapted);
    }

    /** 具体类型（如补充专辑 101）在网页通道原样透传 */
    @Test
    public void webPayloadKeepsConcreteTypes() throws Exception {
        for (int type : new int[]{
                NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT,
                NetEaseWikiClient.TYPE_SONG,
                NetEaseWikiClient.TYPE_ALBUM,
                NetEaseWikiClient.TYPE_ARTIST,
                NetEaseWikiClient.TYPE_LYRIC,
                NetEaseWikiClient.TYPE_LYRIC_TRANSLATION}) {
            JSONObject original = payloadWithType(type);
            JSONObject adapted = NetEaseWikiClient.webPayload(original);
            assertEquals("type=" + type + " 应原样保留", type, adapted.optInt("type"));
            assertSame("非「全部」时无需复制，直接透传", original, adapted);
        }
    }

    /** 无 type（如统计接口）与 null 载荷不得报错 */
    @Test
    public void webPayloadHandlesMissingTypeAndNull() throws Exception {
        JSONObject blank = new JSONObject();
        assertSame(blank, NetEaseWikiClient.webPayload(blank));
        assertEquals(null, NetEaseWikiClient.webPayload(null));
    }

    /** 审核状态筛选在网页通道同样保留 */
    @Test
    public void webPayloadKeepsAuditStatus() throws Exception {
        JSONObject original = payloadWithType(NetEaseWikiClient.TYPE_ALL);
        original.put("auditStatus", 5);
        JSONObject adapted = NetEaseWikiClient.webPayload(original);
        assertFalse(adapted.has("type"));
        assertEquals(5, adapted.optInt("auditStatus"));
    }

    /** 「全部」页必须补齐的来源类型：歌词与翻译 */
    @Test
    public void allExtraTypesCoverLyricAndTranslation() {
        assertEquals(2, NetEaseWikiClient.ALL_EXTRA_TYPES.length);
        assertEquals(NetEaseWikiClient.TYPE_LYRIC, NetEaseWikiClient.ALL_EXTRA_TYPES[0]);
        assertEquals(NetEaseWikiClient.TYPE_LYRIC_TRANSLATION, NetEaseWikiClient.ALL_EXTRA_TYPES[1]);
    }

    /** 类型标签：6 个已收录类型各有独立文案，未收录类型返回 0（调用方隐藏标签） */
    @Test
    public void typeLabelResCoversKnownTypesOnly() {
        int[] known = {
                NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT,
                NetEaseWikiClient.TYPE_SONG,
                NetEaseWikiClient.TYPE_ALBUM,
                NetEaseWikiClient.TYPE_ARTIST,
                NetEaseWikiClient.TYPE_LYRIC,
                NetEaseWikiClient.TYPE_LYRIC_TRANSLATION,
        };
        List<Integer> seen = new ArrayList<>();
        for (int type : known) {
            int res = NetEaseWikiClient.typeLabelRes(type);
            assertTrue("类型 " + type + " 应有标签文案", res != 0);
            assertFalse("类型 " + type + " 的标签文案不应重复", seen.contains(res));
            seen.add(res);
        }
        assertEquals(0, NetEaseWikiClient.typeLabelRes(NetEaseWikiClient.TYPE_ALL));
        assertEquals(0, NetEaseWikiClient.typeLabelRes(-1));
        assertEquals(0, NetEaseWikiClient.typeLabelRes(4));
        assertEquals(0, NetEaseWikiClient.typeLabelRes(103));
    }

    /** 聚合去重：同一条记录（同 bizId + 同提交时间）跨来源只保留一条 */
    @Test
    public void mergeAndDedupeCollapsesSameRecordAcrossSources() throws Exception {
        Contribution fromAll = item("{\"bizId\":100,\"name\":\"Song A\",\"createTime\":2000}",
                NetEaseWikiClient.TYPE_ALL);
        Contribution fromLyricTab = item("{\"bizId\":100,\"name\":\"Song A\",\"createTime\":2000}",
                NetEaseWikiClient.TYPE_LYRIC);

        List<Contribution> merged = NetEaseWikiClient.mergeAndDedupe(
                Arrays.asList(fromAll, fromLyricTab));
        assertEquals(1, merged.size());
    }

    /**
     * 聚合结果契约：歌词与翻译即使 bizId、名称相同也必须各自保留，并按提交时间倒序排列。
     */
    @Test
    public void mergeAndDedupeKeepsLyricAndTranslationAndSortsByTimeDesc() throws Exception {
        Contribution song = item("{\"id\":1,\"bizId\":100,\"name\":\"Song A\",\"type\":3,\"createTime\":1000}",
                NetEaseWikiClient.TYPE_ALL);
        Contribution lyric = item("{\"bizId\":100,\"name\":\"Song A\",\"createTime\":2000}",
                NetEaseWikiClient.TYPE_LYRIC);
        Contribution translation = item("{\"bizId\":100,\"name\":\"Song A\",\"createTime\":3000}",
                NetEaseWikiClient.TYPE_LYRIC_TRANSLATION);

        List<Contribution> merged = NetEaseWikiClient.mergeAndDedupe(
                Arrays.asList(song, lyric, translation));

        assertEquals(3, merged.size());
        assertEquals(3000L, merged.get(0).createTime);
        assertEquals(2000L, merged.get(1).createTime);
        assertEquals(1000L, merged.get(2).createTime);
        assertEquals(NetEaseWikiClient.TYPE_LYRIC_TRANSLATION, merged.get(0).routeType());
        assertEquals(NetEaseWikiClient.TYPE_LYRIC, merged.get(1).routeType());
        assertEquals(NetEaseWikiClient.TYPE_SONG, merged.get(2).routeType());
    }

    /** 聚合入口对 null / 空列表 / 含 null 元素的输入必须安全 */
    @Test
    public void mergeAndDedupeHandlesEmptyAndNullEntries() throws Exception {
        assertTrue(NetEaseWikiClient.mergeAndDedupe(null).isEmpty());
        assertTrue(NetEaseWikiClient.mergeAndDedupe(new ArrayList<Contribution>()).isEmpty());

        List<Contribution> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(item("{\"bizId\":7,\"name\":\"n\",\"createTime\":1}", NetEaseWikiClient.TYPE_SONG));
        assertEquals(1, NetEaseWikiClient.mergeAndDedupe(withNull).size());
    }

    private static Contribution item(String json, int sourceType) throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject(json));
        c.sourceType = sourceType;
        return c;
    }
}
