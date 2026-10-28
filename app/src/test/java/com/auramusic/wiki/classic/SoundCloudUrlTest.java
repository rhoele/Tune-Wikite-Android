package com.auramusic.wiki.classic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 声云链接类型判断。
 *
 * <p>播放列表内单曲的分享链接形如 {@code /user/track?in=user/sets/xxx}，
 * 查询串里出现 {@code /sets/} 不代表这是一条播放列表链接。
 */
public class SoundCloudUrlTest {

    @Test
    public void trackSharedFromSetIsNotTreatedAsSet() {
        assertFalse(SoundCloudSetClient.looksLikeSet(
                "https://soundcloud.com/raiwinnn/retrou-nouveau?in=nexicide/sets/heartwood"));
    }

    @Test
    public void setAndPlaylistLinksAreDetected() {
        assertTrue(SoundCloudSetClient.looksLikeSet(
                "https://soundcloud.com/nexicide/sets/heartwood"));
        assertTrue(SoundCloudSetClient.looksLikeSet(
                "https://soundcloud.com/user/playlists/123?si=abc"));
        assertTrue(SoundCloudSetClient.looksLikeSet("https://on.soundcloud.com/abcd"));
    }

    @Test
    public void anchorDoesNotChangeType() {
        assertFalse(SoundCloudSetClient.looksLikeSet(
                "https://soundcloud.com/user/track#sets/heartwood"));
    }
}
