package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** albumVo 组装契约：字段名、碟分组与曲序必须与原 APK 一致。 */
public class AlbumDraftTest {

    @Test
    public void buildsAlbumVoWithDiscGrouping() throws Exception {
        AlbumDraft.Artist artist = new AlbumDraft.Artist(1001L, "Artist A", "");
        AlbumDraft.Artist artist2 = new AlbumDraft.Artist(1002L, "Artist B", "");

        List<AlbumDraft.Song> songs = new ArrayList<>();
        songs.add(song("Track 2", 2, artist));
        songs.add(song("Track 1", 1, artist));
        songs.add(song("Disc 2 Track 1", 2, artist2));

        JSONObject vo = AlbumDraft.toAlbumVo("Album", "Sub;Alias", "专辑", "Trans",
                Arrays.asList(artist, artist2), "Company", 1700000000000L, "华语",
                Arrays.asList(new AlbumDraft.Tag("1002", "电子")), "Producer", songs,
                "https://example.com", "notes",
                null, null);

        assertEquals("Album", vo.getString("albumName"));
        assertEquals("Sub;Alias", vo.getString("subtitle"));
        assertEquals("专辑", vo.getString("albumType"));
        assertEquals("Company", vo.getString("company"));
        assertEquals(1700000000000L, vo.getLong("publishTime"));
        assertEquals(2, vo.getJSONArray("artistIds").length());
        assertEquals(1, vo.getJSONArray("songTags").length());

        JSONArray list = vo.getJSONArray("albumSongDiscVoList");
        assertEquals(3, list.length());
        assertTrue(vo.getJSONArray("albumSongDiscs").length() == list.length());

        JSONObject secondDisc = list.getJSONObject(2);
        assertEquals(2, secondDisc.getInt("disc"));
        assertEquals(2, secondDisc.getInt("no"));
        assertEquals("Disc 2 Track 1", secondDisc.getString("songName"));
        assertEquals("华语", secondDisc.getString("language"));
        assertEquals(1, secondDisc.getJSONArray("artistRepVos").length());
        assertEquals(1002L, secondDisc.getJSONArray("artistRepVos")
                .getJSONObject(0).getLong("artistId"));
        assertEquals("Track 2", list.getJSONObject(1).getString("songName"));
    }

    @Test
    public void writesNosKeysAndAudioPayload() throws Exception {
        AlbumDraft.Artist artist = new AlbumDraft.Artist(1L, "A", "");
        AlbumDraft.Song song = song("Track", 1, artist);
        song.nosFile = new NosUploader.NosFile("a.m4a", 123L, "audio/mp4",
                "ymusic", "obj-key", "doc", "res", "md5");

        JSONObject vo = AlbumDraft.toAlbumVo("Album", "", "专辑", "",
                Arrays.asList(artist), "Company", 1L, "英语",
                new ArrayList<>(), "", Arrays.asList(song), "src", "", null, null);

        JSONObject audio = vo.getJSONArray("albumSongDiscVoList")
                .getJSONObject(0).getJSONObject("audio");
        assertEquals("ymusic/obj-key", audio.getString("nosKey"));
        assertEquals(123L, audio.getLong("fileSize"));
        assertEquals("audio/mp4", audio.getString("type"));
        // 没有封面时不应写入空 key
        assertTrue(!vo.has("albumRawNosKey"));
        assertTrue(!vo.has("album1v1NosKey"));
    }

    private AlbumDraft.Song song(String name, int disc, AlbumDraft.Artist artist) {
        AlbumDraft.Song song = new AlbumDraft.Song();
        song.name = name;
        song.disc = disc;
        song.language = "华语";
        song.artists.add(artist);
        return song;
    }
}
