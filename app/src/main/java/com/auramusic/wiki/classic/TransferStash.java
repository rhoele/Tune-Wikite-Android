package com.auramusic.wiki.classic;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「声云下载 → 专辑上传」的内存交接区。
 *
 * <p>两个页面同处一个 Activity，下载页完成后把内容放入这里并通知宿主切换标签，
 * 上传页在下次可见时消费。文件本身保存在应用私有目录，卸载即清理。
 *
 * <p>set（播放列表）会一次交接多首，列表顺序即曲序；单曲下载等价于只有一条的列表。
 */
final class TransferStash {

    static final class Track {
        final File file;
        final String title;
        final String artist;

        Track(File file, String title, String artist) {
            this.file = file;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }
    }

    private static final List<Track> tracks = new ArrayList<>();
    private static File cover;
    private static String sourceUrl = "";

    private TransferStash() {
    }

    /**
     * 放入一次交接内容。
     *
     * @param newTracks 已下载音频（按曲序，可为空）
     * @param coverFile 专辑封面（可为空）
     * @param url       声云链接，写入上传页的「资料来源」（可为空）
     */
    static void put(List<Track> newTracks, File coverFile, String url) {
        tracks.clear();
        if (newTracks != null) {
            tracks.addAll(newTracks);
        }
        cover = coverFile;
        sourceUrl = url == null ? "" : url.trim();
    }

    static List<Track> tracks() {
        List<Track> out = new ArrayList<>();
        for (Track track : tracks) {
            if (track.file != null && track.file.isFile() && track.file.length() > 0) {
                out.add(track);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static File cover() {
        return cover != null && cover.isFile() ? cover : null;
    }

    static String sourceUrl() {
        return sourceUrl == null ? "" : sourceUrl;
    }

    /** 是否至少有一项可交接内容（音频 / 封面 / 资料来源可独立传入）。 */
    static boolean hasContent() {
        return !tracks().isEmpty() || cover() != null || !sourceUrl().isEmpty();
    }

    /** 消费一次，避免每次切回上传页都重复添加同一批歌曲。 */
    static void clear() {
        tracks.clear();
        cover = null;
        sourceUrl = "";
    }
}
