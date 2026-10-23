package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 「我的贡献」条目解析与容错的单测。
 *
 * <p>覆盖两个 P0 问题的关键链路：
 * <ul>
 *   <li><b>闪退</b>：接口数据异常解析不得抛异常（脏数据只跳过该条）；</li>
 *   <li><b>「全部」空白</b>：封面字段 {@code nosKey} 的识别（旧实现缺失该键，
 *       导致所有卡片都走「无封面」分支）。</li>
 * </ul>
 *
 * <p>数据样本取自 {@code /api/rep/ugc/detail} 真实返回契约。
 */
public class ContributionParseTest {

    /** 实测真实条目（节选关键字段） */
    private static final String REAL_ITEM = "{"
            + "\"id\":13006677,"
            + "\"bizId\":3365925110,"
            + "\"name\":\"PorterRobinson-Unison(IpsiomRemix).mp3\","
            + "\"createTime\":1789633891000,"
            + "\"nosKey\":\"http://p2.music.126.net/UeTuwE7pvjBpypWLudqukA==/3132508627578625.jpg\","
            + "\"status\":5,"
            + "\"type\":3,"
            + "\"artistSimpleVos\":[{\"name\":\"Porter Robinson\"}]"
            + "}";

    @Test
    public void resolvesRealCoverKeyNosKey() throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject(REAL_ITEM));
        assertNotNull(c);
        assertEquals("3365925110", c.targetId);
        assertEquals("http://p2.music.126.net/UeTuwE7pvjBpypWLudqukA==/3132508627578625.jpg",
                c.coverUrl);
        assertTrue(CoverLoader.isAllowed(c.coverUrl));
    }

    @Test
    public void relativeCoverPathIsCompletedToCdn() throws Exception {
        Contribution c = Contribution.fromJson(
                new JSONObject("{\"bizId\":1,\"nosKey\":\"yyimgs/obj/w5zDlMODwrDDiGjCn8Ky/14395847070a1c3.jpg\"}"));
        assertEquals("https://p1.music.126.net/yyimgs/obj/w5zDlMODwrDDiGjCn8Ky/14395847070a1c3.jpg",
                c.coverUrl);
        assertTrue(CoverLoader.isAllowed(c.coverUrl));
    }

    @Test
    public void protocolRelativeCoverIsCompleted() throws Exception {
        Contribution c = Contribution.fromJson(
                new JSONObject("{\"bizId\":1,\"nosKey\":\"//p3.music.126.net/x.jpg\"}"));
        assertEquals("https://p3.music.126.net/x.jpg", c.coverUrl);
        assertTrue(CoverLoader.isAllowed(c.coverUrl));
    }

    @Test
    public void nestedCoverIsResolved() throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject(
                "{\"bizId\":2,\"song\":{\"album\":{\"picUrl\":\"http://p1.music.126.net/a.jpg\"}}}"));
        assertEquals("http://p1.music.126.net/a.jpg", c.coverUrl);
    }

    @Test
    public void artistSimpleVosIsJoined() throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject(
                "{\"bizId\":3,\"artistSimpleVos\":[{\"name\":\"A\"},{\"name\":\"B\"},null]}"));
        assertEquals("A / B", c.displayArtist());
    }

    @Test
    public void unknownStatusFallsBackToUnknown() throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject("{\"bizId\":4,\"status\":999}"));
        assertEquals(Contribution.Status.UNKNOWN, c.statusEnum());
    }

    @Test
    public void emptyNameFallsBackToPlaceholder() throws Exception {
        Contribution c = Contribution.fromJson(new JSONObject("{\"bizId\":5}"));
        assertEquals("未命名内容", c.displayName());
        assertEquals("", c.displayArtist());
    }

    @Test
    public void dirtyEntriesAreSkippedWithoutThrowing() throws Exception {
        // 混入 null / 字符串 / 数组 / 数字等非对象元素，均不得抛出异常
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject("{\"bizId\":1,\"name\":\"ok1\"}"));
        arr.put("not-an-object");
        arr.put(JSONObject.NULL);
        arr.put(12345);
        arr.put(new JSONArray());
        arr.put(new JSONObject("{\"bizId\":2,\"name\":\"ok2\"}"));

        List<Contribution> list = Contribution.listFrom(arr);
        assertEquals(2, list.size());
        assertEquals("ok1", list.get(0).name);
        assertEquals("ok2", list.get(1).name);
    }

    @Test
    public void nullArrayYieldsEmptyList() {
        assertTrue(Contribution.listFrom(null).isEmpty());
    }

    /** 有服务端 id 时，stableKey 直接用该 id（服务端内全量唯一） */
    @Test
    public void stableKeyPrefersServerId() throws Exception {
        Contribution c = Contribution.fromJson(
                new JSONObject("{\"id\":13006677,\"bizId\":3365925110,\"name\":\"x\"}"));
        assertEquals("13006677", c.stableKey());
    }

    /**
     * 歌词与翻译同属一首歌曲：{@code bizId} 与名称完全相同，只有 {@code createTime} 不同，
     * 必须被判为两条不同的记录（否则 DiffUtil 会让两者互相覆盖，「只显示一条」）。
     */
    @Test
    public void lyricAndTranslationOfSameSongHaveDistinctKeys() throws Exception {
        Contribution lyric = Contribution.fromJson(new JSONObject(
                "{\"bizId\":3442845953,\"name\":\"Song A\",\"type\":0,\"createTime\":1792220066050}"));
        Contribution translation = Contribution.fromJson(new JSONObject(
                "{\"bizId\":3442845953,\"name\":\"Song A\",\"type\":0,\"createTime\":1792220068060}"));

        assertTrue("歌词与翻译不能判为同一条", !lyric.stableKey().equals(translation.stableKey()));
    }

    /** 来源类型不参与 stableKey：同一歌词来自「全部」与「歌词」两个查询时必须能去重 */
    @Test
    public void sourceTypeDoesNotAffectStableKey() throws Exception {
        Contribution fromAll = Contribution.fromJson(new JSONObject(
                "{\"bizId\":3442845953,\"name\":\"Song A\",\"type\":0,\"createTime\":1792220066050}"));
        Contribution fromLyricTab = Contribution.fromJson(new JSONObject(
                "{\"bizId\":3442845953,\"name\":\"Song A\",\"type\":0,\"createTime\":1792220066050}"));
        fromAll.sourceType = NetEaseWikiClient.TYPE_ALL;
        fromLyricTab.sourceType = NetEaseWikiClient.TYPE_LYRIC;

        assertEquals(fromAll.stableKey(), fromLyricTab.stableKey());
    }

    /** 跳转类型：条目自身 type 优先，缺失（歌词 / 翻译恒为 0）时回落到来源查询类型 */
    @Test
    public void routeTypeFallsBackToSourceType() throws Exception {
        Contribution song = Contribution.fromJson(new JSONObject("{\"bizId\":1,\"type\":3}"));
        assertEquals(NetEaseWikiClient.TYPE_SONG, song.routeType());

        Contribution lyric = Contribution.fromJson(new JSONObject("{\"bizId\":1,\"type\":0}"));
        lyric.sourceType = NetEaseWikiClient.TYPE_LYRIC;
        assertEquals(NetEaseWikiClient.TYPE_LYRIC, lyric.routeType());

        Contribution translation = Contribution.fromJson(new JSONObject("{\"bizId\":1,\"type\":0}"));
        translation.sourceType = NetEaseWikiClient.TYPE_LYRIC_TRANSLATION;
        assertEquals(NetEaseWikiClient.TYPE_LYRIC_TRANSLATION, translation.routeType());
    }

    /**
     * 压力测试：反复解析「混合脏数据 + 合法数据」的大页，验证解析路径稳定不抛异常。
     * 对应需求 1 中「接口数据异常解析导致闪退」的风险点。
     */
    @Test
    public void repeatedParsingIsStableUnderStress() throws Exception {
        JSONArray page = new JSONArray();
        for (int i = 0; i < 200; i++) {
            if (i % 7 == 0) {
                page.put("hostile-" + i);
            } else if (i % 11 == 0) {
                page.put(new JSONArray());
            } else {
                page.put(new JSONObject("{\"bizId\":" + i
                        + ",\"name\":\"n" + i + "\",\"status\":" + (i % 6)
                        + ",\"nosKey\":\"http://p2.music.126.net/i" + i + ".jpg\"}"));
            }
        }

        for (int round = 0; round < 500; round++) {
            List<Contribution> list = Contribution.listFrom(page);
            assertFalse(list.isEmpty());
            for (Contribution c : list) {
                assertNotNull(c.displayName());
                assertNotNull(c.displayArtist());
                assertNotNull(c.statusEnum());
            }
        }
    }
}
