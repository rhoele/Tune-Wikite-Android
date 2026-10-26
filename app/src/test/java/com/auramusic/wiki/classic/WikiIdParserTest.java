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
