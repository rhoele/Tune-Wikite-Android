package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 歌曲百科 JSON 映射与兜底的单测（覆盖 G2/G19）。
 * 数据样本取自真实接口实测契约。
 */
public class SongWikiTest {

    private static final String REAL_PAYLOAD = "{"
            + "\"ok\":true,"
            + "\"data\":{"
            + "  \"songId\":347230,"
            + "  \"songName\":\"海阔天空\","
            + "  \"artistName\":[\"Beyond\"],"
            + "  \"coverUrl\":\"https://p4.music.126.net/abc.jpg\","
            + "  \"lang\":\"zh-hk\","
            + "  \"ugc\":{\"coverType\":\"原唱\",\"duration\":\"05:26.000\","
            + "           \"transName\":\"Boundless Oceans, Vast Skies\","
            + "           \"locks\":[\"锁发行时间\",\"锁曲风\"]},"
            + "  \"wiki\":{\"bpm\":\"76\",\"language\":\"粤语\",\"releaseDate\":\"1993-09-09\","
            + "            \"releaseVersion\":\"录音室版\","
            + "            \"genres\":[\"摇滚-流行摇滚\",\"流行\"],"
            + "            \"moods\":[\"治愈\",\"悲伤\",\"自信\"],"
            + "            \"entertainment\":[\"电影《中国合伙人》插曲\"],"
            + "            \"creators\":[{\"role\":\"作词\","
            + "                          \"people\":[{\"artistId\":\"189688\",\"name\":\"黄家驹\"}]}]},"
            + "  \"records\":[{\"nickName\":\"_TRUMAN-\",\"userId\":3259850304,"
            + "               \"createTime\":1730051809000,"
            + "               \"approveNames\":[\"获奖成就\"],"
            + "               \"userImg\":\"https://p4.music.126.net/u.jpg\"}]"
            + "}}";

    @Test
    public void parsesRealContractFully() throws Exception {
        SongWiki w = SongWiki.fromJson(REAL_PAYLOAD);

        assertEquals(347230L, w.songId);
        assertEquals("海阔天空", w.songName);
        assertEquals("Beyond", w.artistNames.get(0));
        assertEquals("zh-hk", w.lang);
        assertEquals("原唱", w.coverType);
        assertEquals("05:26.000", w.duration);
        assertEquals(2, w.locks.size());
        assertEquals("76", w.bpm);
        assertEquals("粤语", w.language);
        assertEquals("1993-09-09", w.releaseDate);
        assertEquals(2, w.genres.size());
        assertEquals(3, w.moods.size());
        assertEquals(1, w.entertainment.size());
        assertEquals(1, w.creators.size());
        assertEquals("作词", w.creators.get(0).role);
        assertEquals("黄家驹", w.creators.get(0).people.get(0).name);
        assertEquals("189688", w.creators.get(0).people.get(0).artistId);
        assertEquals(1, w.records.size());
        assertEquals(3259850304L, w.records.get(0).userId);
        assertEquals("获奖成就", w.records.get(0).approveNames.get(0));
        assertEquals(REAL_PAYLOAD, w.rawJson);
    }

    @Test
    public void missingOptionalBlocksDegradeGracefully() throws Exception {
        // 仅保留最小必要字段：ugc / wiki / records 全缺失
        SongWiki w = SongWiki.fromJson("{\"data\":{\"songId\":1,\"songName\":\"X\"}}");
        assertEquals(1L, w.songId);
        assertEquals("X", w.songName);
        assertTrue(w.creators.isEmpty());
        assertTrue(w.records.isEmpty());
        assertEquals("", w.bpm);
        assertEquals("", w.coverType);
    }

    @Test
    public void displayFallbacksForEmptyFields() throws Exception {
        SongWiki w = SongWiki.fromJson("{\"data\":{\"songId\":2}}");
        assertEquals("未知歌曲", w.displayName());
        assertEquals("未知歌手", w.displayArtists());
    }

    @Test
    public void numericFieldsSerializedAsStringAreAccepted() throws Exception {
        SongWiki w = SongWiki.fromJson(
                "{\"data\":{\"songId\":3,\"songName\":\"Y\",\"wiki\":{\"bpm\":120}}}");
        assertEquals("120", w.bpm);
    }

    @Test
    public void nullArtistEntriesAreFilteredOut() throws Exception {
        SongWiki w = SongWiki.fromJson(
                "{\"data\":{\"songId\":4,\"songName\":\"Z\",\"artistName\":[\"A\",null,\"B\"]}}");
        assertEquals(2, w.artistNames.size());
        assertEquals("A", w.artistNames.get(0));
        assertEquals("B", w.artistNames.get(1));
    }

    @Test
    public void emptyDataThrowsEmptyKind() {
        WikiException e = assertThrows(WikiException.class,
                () -> SongWiki.fromJson("{\"ok\":false}"));
        assertEquals(WikiException.Kind.EMPTY, e.getKind());
    }

    @Test
    public void malformedJsonThrowsBadJsonKind() {
        WikiException e = assertThrows(WikiException.class,
                () -> SongWiki.fromJson("<html>502 Bad Gateway</html>"));
        assertEquals(WikiException.Kind.BAD_JSON, e.getKind());
    }

    @Test
    public void blankBodyThrowsBadJsonKind() {
        WikiException e = assertThrows(WikiException.class,
                () -> SongWiki.fromJson("   "));
        assertEquals(WikiException.Kind.BAD_JSON, e.getKind());
    }
}
