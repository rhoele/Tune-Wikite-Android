package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 歌曲 ID / 分享链接归一化解析的单测（覆盖 G4 全部输入形态）。
 */
public class WikiIdParserTest {

    @Test
    public void pureNumericIdIsAccepted() {
        assertEquals(347230L, WikiIdParser.parse("347230"));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertEquals(347230L, WikiIdParser.parse("   347230  "));
    }

    @Test
    public void linkWithIdParameterIsParsed() {
        assertEquals(347230L,
                WikiIdParser.parse("https://music.163.com/song?id=347230&userid=12345"));
    }

    @Test
    public void linkWithHashIdParameterIsParsed() {
        assertEquals(123456L,
                WikiIdParser.parse("https://music.163.com/#/song?id=123456"));
    }

    @Test
    public void linkWithSongIdParameterIsParsed() {
        assertEquals(987654L,
                WikiIdParser.parse("https://music.163.com/song?songId=987654"));
    }

    @Test
    public void artistShareTextIsParsed() {
        String share = "分享歌手: Inaharu 快来听Inaharu的热门单曲吧 "
                + "https://y.music.163.com/m/artist?id=56949793 （来自@网易云音乐）";
        assertEquals(56949793L, WikiIdParser.parseArtistId(share));
    }

    @Test
    public void artistLinkVariantsAreParsed() {
        assertEquals(56949793L,
                WikiIdParser.parseArtistId("https://music.163.com/#/artist?id=56949793"));
        assertEquals(56949793L,
                WikiIdParser.parseArtistId("https://music.163.com/m/artist?id=56949793"));
        assertEquals(56949793L,
                WikiIdParser.parseArtistId("https://music.163.com/artist?id=56949793"));
    }

    @Test
    public void plainKeywordHasNoArtistId() {
        assertEquals(-1L, WikiIdParser.parseArtistId("Inaharu"));
        assertEquals(-1L, WikiIdParser.parseArtistId(""));
    }

    @Test
    public void artistIdFromPlainNumberAndExtraQuery() {
        assertEquals(56949793L, WikiIdParser.parseArtistId("56949793"));
        assertEquals(56949793L, WikiIdParser.parseArtistId(
                "https://music.163.com/artist?userid=1&id=56949793&utm_source=x"));
        // 链接末尾被一起复制的标点不影响解析
        assertEquals(56949793L, WikiIdParser.parseArtistId(
                "https://music.163.com/artist?id=56949793，"));
    }

    @Test
    public void artistShareTextWithMultipleLinksPicksArtist() {
        String share = "分享歌手: Inaharu\n"
                + "https://music.163.com/song?id=111111\n"
                + "https://y.music.163.com/m/artist?id=56949793 （来自@网易云音乐）";
        assertEquals(56949793L, WikiIdParser.parseArtistId(share));
    }

    @Test
    public void nonArtistLinksAreNotMistakenForArtist() {
        assertEquals(-1L, WikiIdParser.parseArtistId("https://music.163.com/song?id=347230"));
        assertEquals(-1L, WikiIdParser.parseArtistId("https://music.163.com/album?id=347230"));
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com/playlist?id=347230"));
        // 歌手名出现在 query 里但路径不是艺人页
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com/song?id=1&artist=347230"));
    }

    @Test
    public void invalidHostsAndIdsAreRejected() {
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com.evil.com/artist?id=56949793"));
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://example.com/artist?id=56949793"));
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com/artist?id=0"));
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com/artist?id=abc"));
        assertEquals(-1L, WikiIdParser.parseArtistId(
                "https://music.163.com/artist?name=Inaharu"));
    }

    @Test
    public void sameShareTextYieldsSameIdForAlbumAndSongEntries() {
        // 专辑艺人（ArtistPickerDialog）与单曲艺人（歌曲编辑弹窗）共用同一解析核心，
        // 同一份粘贴内容必须得到完全一致的艺人 ID。
        String share = "分享歌手: Inaharu https://y.music.163.com/m/artist?id=56949793 （来自@网易云音乐）";
        long fromAlbum = WikiIdParser.parseArtistId(share);
        long fromSong = WikiIdParser.parseArtistId(share);
        assertEquals(56949793L, fromAlbum);
        assertEquals(fromAlbum, fromSong);
    }

    @Test
    public void shareTextWithLooseDigitsIsParsed() {
        assertEquals(347230L, WikiIdParser.parse("分享单曲 347230 来自网易云音乐"));
    }

    @Test
    public void leadingZeroIsRejected() {
        assertEquals(-1L, WikiIdParser.parse("0347230"));
    }

    @Test
    public void trailingLettersAreRejected() {
        assertEquals(-1L, WikiIdParser.parse("347230abc"));
    }

    @Test
    public void emptyAndNullAreRejected() {
        assertEquals(-1L, WikiIdParser.parse(""));
        assertEquals(-1L, WikiIdParser.parse("   "));
        assertEquals(-1L, WikiIdParser.parse(null));
    }

    @Test
    public void shareTextWithoutDigitsIsRejected() {
        assertEquals(-1L, WikiIdParser.parse("分享单曲 http://163cn.tv/abc (来自@网易云音乐)"));
    }

    @Test
    public void normalizeReturnsNullOnFailure() {
        assertNull(WikiIdParser.normalize("not-an-id"));
        assertEquals("347230", WikiIdParser.normalize("347230"));
    }

    @Test
    public void isValidOnlyForPositiveId() {
        assertTrue(WikiIdParser.isValid(1L));
        assertFalse(WikiIdParser.isValid(0L));
        assertFalse(WikiIdParser.isValid(-1L));
    }
}
