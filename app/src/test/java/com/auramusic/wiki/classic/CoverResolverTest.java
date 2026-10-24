package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 封面补齐的解析契约。
 *
 * <p>歌词 / 翻译没有封面字段，统一按 bizId 取所属歌曲的专辑封面；
 * 分类页同理。这里锁定三个公开详情端点的字段位置，避免字段改名后静默失效。
 */
public class CoverResolverTest {

    @Test
    public void parsesSongCoversKeyedBySongId() throws Exception {
        String json = "{\"code\":200,\"songs\":["
                + "{\"id\":3342981041,\"name\":\"铁花飞\","
                + "\"album\":{\"id\":359634960,\"picUrl\":\"https://p2.music.126.net/a.jpg\"}},"
                + "{\"id\":186016,\"name\":\"晴天\","
                + "\"album\":{\"id\":19321,\"picUrl\":\"https://p2.music.126.net/b.jpg\"}}"
                + "]}";
        Map<String, String> out = new LinkedHashMap<>();
        CoverResolver.parseSongCovers(json, out);

        assertEquals(2, out.size());
        assertEquals("https://p2.music.126.net/a.jpg", out.get("song:3342981041"));
        assertEquals("https://p2.music.126.net/b.jpg", out.get("song:186016"));
    }

    @Test
    public void skipsSongsWithoutAlbumCover() throws Exception {
        String json = "{\"songs\":[{\"id\":1,\"album\":{}},{\"id\":2}]}";
        Map<String, String> out = new LinkedHashMap<>();
        CoverResolver.parseSongCovers(json, out);
        assertTrue(out.isEmpty());
    }

    @Test
    public void parsesAlbumCover() throws Exception {
        String json = "{\"album\":{\"id\":32311,\"name\":\"神的游戏\","
                + "\"picUrl\":\"https://p1.music.126.net/c.jpg\"}}";
        assertEquals("https://p1.music.126.net/c.jpg", CoverResolver.parseAlbumCover(json));
        assertEquals("", CoverResolver.parseAlbumCover("{}"));
    }

    @Test
    public void parsesArtistCoverWithFallback() throws Exception {
        String withPic = "{\"artist\":{\"id\":6452,\"picUrl\":\"https://p2.music.126.net/d.jpg\"}}";
        assertEquals("https://p2.music.126.net/d.jpg", CoverResolver.parseArtistCover(withPic));

        String onlyImg1v1 = "{\"artist\":{\"id\":6452,"
                + "\"img1v1Url\":\"https://p2.music.126.net/e.jpg\"}}";
        assertEquals("https://p2.music.126.net/e.jpg", CoverResolver.parseArtistCover(onlyImg1v1));

        assertEquals("", CoverResolver.parseArtistCover("{\"code\":404}"));
    }
}
