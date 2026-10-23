package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 贡献卡片跳转链路的单测（需求 2：URL 标准化）。
 *
 * <p>固化的契约：链接必须由<b>条目自身类型</b>（{@link Contribution#routeType()}）决定，
 * 而不是当前「分类」标签。此前用「分类」生成链接，「全部」页（分类值 {@code 0}）的所有
 * 卡片都被拼成 {@code /song?id=}。
 *
 * <p>样本 bizId 取自 {@code .diag/route-probe.js} 的真实接口数据。
 */
public class WikiLinksTest {

    /** 艺人条目 → 艺人主页 */
    @Test
    public void artistTypeRoutesToArtistPage() {
        assertEquals("https://music.163.com/artist?id=53478021",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_ARTIST, 53478021L));
    }

    /** 专辑条目 → 专辑页 */
    @Test
    public void albumTypeRoutesToAlbumPage() {
        assertEquals("https://music.163.com/album?id=385281824",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_ALBUM, 385281824L));
    }

    /** 补充专辑条目 → 专辑页 */
    @Test
    public void albumSupplementRoutesToAlbumPage() {
        assertEquals("https://music.163.com/album?id=12894129",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT, 12894129L));
    }

    /** 歌曲 / 歌词 / 翻译歌词 → 单曲页（歌词与翻译依附于歌曲） */
    @Test
    public void songLyricAndTranslationRouteToSongPage() {
        assertEquals("https://music.163.com/song?id=3365925110",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_SONG, 3365925110L));
        assertEquals("https://music.163.com/song?id=3365925110",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_LYRIC, 3365925110L));
        assertEquals("https://music.163.com/song?id=3365925110",
                WikiLinks.urlFor(NetEaseWikiClient.TYPE_LYRIC_TRANSLATION, 3365925110L));
    }

    /**
     * 「全部」页场景：分类值为 {@code 0}，但每条卡片按自身类型生成链接，
     * 结果必须与在对应分类页点击时完全一致。
     */
    @Test
    public void itemsInAllTabUseTheirOwnType() throws Exception {
        Contribution artist = item("{\"bizId\":53478021,\"type\":1}");
        Contribution album = item("{\"bizId\":385281824,\"type\":2}");
        Contribution song = item("{\"id\":7,\"bizId\":3365925110,\"type\":3}");

        assertEquals(WikiLinks.urlFor(NetEaseWikiClient.TYPE_ARTIST, 53478021L),
                WikiLinks.urlFor(artist.routeType(), 53478021L));
        assertEquals(WikiLinks.urlFor(NetEaseWikiClient.TYPE_ALBUM, 385281824L),
                WikiLinks.urlFor(album.routeType(), 385281824L));
        assertEquals(WikiLinks.urlFor(NetEaseWikiClient.TYPE_SONG, 3365925110L),
                WikiLinks.urlFor(song.routeType(), 3365925110L));
    }

    /** 歌词 / 翻译在「全部」页由来源查询类型补齐，仍指向所属歌曲页 */
    @Test
    public void lyricFromAllTabStillRoutesToSongPage() throws Exception {
        Contribution lyric = item("{\"bizId\":3442845953,\"type\":0}");
        lyric.sourceType = NetEaseWikiClient.TYPE_LYRIC;

        assertEquals("https://music.163.com/song?id=3442845953",
                WikiLinks.urlFor(lyric.routeType(), 3442845953L));
    }

    /** 未收录 / 未知类型兜底为单曲页，不得抛出异常 */
    @Test
    public void unknownTypesFallBackToSongPage() {
        for (int type : new int[]{NetEaseWikiClient.TYPE_ALL, -1, 4, 103}) {
            assertEquals("type=" + type + " 应兜底为单曲页",
                    "https://music.163.com/song?id=100", WikiLinks.urlFor(type, 100L));
        }
    }

    /** 非法 id（0 / 负数）不生成链接 */
    @Test
    public void invalidIdYieldsNull() {
        assertNull(WikiLinks.urlFor(NetEaseWikiClient.TYPE_SONG, 0L));
        assertNull(WikiLinks.urlFor(NetEaseWikiClient.TYPE_SONG, -1L));
    }

    /** 生成的外链必须通过自身格式校验 */
    @Test
    public void generatedUrlsAreWellFormed() {
        assertTrue(WikiLinks.isWellFormed(WikiLinks.urlFor(NetEaseWikiClient.TYPE_ARTIST, 1L)));
        assertTrue(WikiLinks.isWellFormed(WikiLinks.urlFor(NetEaseWikiClient.TYPE_ALBUM, 1L)));
        assertTrue(WikiLinks.isWellFormed(WikiLinks.urlFor(NetEaseWikiClient.TYPE_SONG, 1L)));
        assertTrue(WikiLinks.isWellFormed(WikiLinks.userUrl(347230L)));

        assertFalse(WikiLinks.isWellFormed(null));
        assertFalse(WikiLinks.isWellFormed(""));
        assertFalse(WikiLinks.isWellFormed("https://music.163.com/song?id=0"));
        assertFalse(WikiLinks.isWellFormed("https://music.163.com/song"));
    }

    /** 用户主页链接格式与原 APK 一致 */
    @Test
    public void userUrlMatchesReference() {
        assertEquals("https://music.163.com/user/home?id=347230", WikiLinks.userUrl(347230L));
    }

    private static Contribution item(String json) throws Exception {
        return Contribution.fromJson(new JSONObject(json));
    }
}
