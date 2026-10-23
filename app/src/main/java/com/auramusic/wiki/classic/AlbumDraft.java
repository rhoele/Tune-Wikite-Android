package com.auramusic.wiki.classic;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 专辑上传的草稿模型（对齐原 APK 的 albumVo 组装逻辑）。
 */
final class AlbumDraft {

    private AlbumDraft() {
    }

    static final class Artist {
        final long id;
        final String name;
        final String avatar;

        Artist(long id, String name, String avatar) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.avatar = avatar == null ? "" : avatar;
        }

        /**
         * 曲库中已存在的艺人（id > 0）按 ID 判等；
         * 「无匹配结果直接添加」产生的自定义艺人 id 为 0，只能按名称区分。
         */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Artist)) {
                return false;
            }
            Artist other = (Artist) o;
            if (id > 0 && other.id > 0) {
                return id == other.id;
            }
            return id == other.id && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return id > 0 ? Long.hashCode(id) : name.hashCode();
        }
    }

    static final class Tag {
        final String id;
        final String name;

        Tag(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Tag && ((Tag) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    static final class Song {
        String name = "";
        String subtitle = "";
        String transName = "";
        int disc = 1;
        int track;
        File file;
        String mime = "audio/mp4";
        final List<Artist> artists = new ArrayList<>();
        /** 被移除的艺人：在选择面板顶部作为首选项，可一键填回 */
        final List<Artist> removedArtists = new ArrayList<>();
        String language = "";
        final List<Tag> tags = new ArrayList<>();

        /** 上传完成后的 NOS 文件信息 */
        NosUploader.NosFile nosFile;
        int uploadPercent;
        String uploadError;
        boolean uploading;

        boolean uploaded() {
            return nosFile != null;
        }
    }

    /** 专辑类型（与原 APK 的 albumVo 取值一致） */
    static final String[] ALBUM_TYPES = {"专辑", "EP", "Single", "精选集", "合集", "DEMO"};

    /**
     * 语种（与原 APK 的下单取值完全一致）。
     *
     * <p>注意这里是「国语」而不是「华语」：服务端按名称匹配语种，写成「华语」会被判为未知语种。
     */
    static final List<String> LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            "国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他", "未知",
            "德语", "法语", "西班牙语", "葡萄牙语", "意大利语", "俄语", "泰语", "越南语",
            "印尼语", "马来语", "菲律宾语", "印地语", "阿拉伯语", "土耳其语", "荷兰语",
            "瑞典语", "芬兰语", "波兰语", "乌克兰语", "拉丁语", "藏语", "维吾尔语",
            "蒙古语", "客家话", "上海话", "四川话", "东北话"));

    /** 折叠状态下常显的常用语种 */
    static final List<String> CORE_LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            "国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他"));

    /** 只能单独选择的语种（原 APK 的同名约束） */
    static final Set<String> EXCLUSIVE_LANGUAGES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("未知", "其他", "纯音乐")));

    /**
     * 更新语种多选集合。
     *
     * <p>「未知 / 其他 / 纯音乐」互斥且只能单独存在；选择了它们会清空其它语种，
     * 选择其它语种时也会先移除这三项。
     */
    static void toggleLanguage(Set<String> selected, String language, boolean checked) {
        if (selected == null || language == null) {
            return;
        }
        if (!checked) {
            selected.remove(language);
            return;
        }
        if (EXCLUSIVE_LANGUAGES.contains(language)) {
            selected.clear();
        } else {
            selected.removeAll(EXCLUSIVE_LANGUAGES);
        }
        selected.add(language);
    }

    /** 组装提交用的 albumVo。 */
    static JSONObject toAlbumVo(String albumName, String subtitle, String albumType,
                                String transName, List<Artist> albumArtists, String company,
                                long publishTime, String language, List<Tag> tags,
                                String production, List<Song> songs, String source, String notes,
                                NosUploader.NosFile rawCover, NosUploader.NosFile squareCover)
            throws Exception {
        List<Song> ordered = new ArrayList<>(songs);
        // 按碟分组后重排曲序：拖动排序的结果直接体现在 no 上
        ordered.sort((a, b) -> Integer.compare(a.disc, b.disc));
        int[] perDisc = new int[16];
        org.json.JSONArray songArray = new org.json.JSONArray();
        for (Song song : ordered) {
            int disc = Math.max(1, Math.min(song.disc, 15));
            perDisc[disc]++;
            JSONObject item = new JSONObject()
                    .put("no", perDisc[disc])
                    .put("songFId", 0)
                    .put("songName", song.name.trim())
                    .put("transName", safe(song.transName))
                    .put("songSubTitle", safe(song.subtitle));
            org.json.JSONArray artistArray = new org.json.JSONArray();
            for (Artist artist : song.artists) {
                artistArray.put(new JSONObject()
                        .put("artistId", artist.id > 0 ? artist.id : JSONObject.NULL)
                        .put("artistName", artist.name));
            }
            org.json.JSONArray tagArray = new org.json.JSONArray();
            for (Tag tag : song.tags) {
                tagArray.put(new JSONObject().put("id", tag.id).put("name", tag.name));
            }
            JSONObject audio = song.nosFile == null ? new JSONObject() : song.nosFile.toJson();
            item.put("artistRepVos", artistArray)
                    .put("language", safe(song.language))
                    .put("albumId", "")
                    .put("albumName", albumName.trim())
                    .put("disc", disc)
                    .put("songTags", tagArray)
                    .put("canEdit", true)
                    .put("changeType", 1)
                    .put("audio", audio)
                    .put("songMusicVo", audio);
            songArray.put(item);
        }

        org.json.JSONArray albumArtistIds = new org.json.JSONArray();
        for (Artist artist : albumArtists) {
            // 原 APK 的 artistIds 是对象数组（artistId + artistName），不是纯 ID 数组
            albumArtistIds.put(new JSONObject()
                    .put("artistId", artist.id > 0 ? artist.id : JSONObject.NULL)
                    .put("artistName", artist.name));
        }
        org.json.JSONArray albumTagArray = new org.json.JSONArray();
        for (Tag tag : tags) {
            albumTagArray.put(new JSONObject().put("id", tag.id).put("name", tag.name));
        }
        JSONObject vo = new JSONObject()
                .put("albumName", albumName.trim())
                .put("subtitle", safe(subtitle))
                .put("albumType", albumType)
                .put("transName", safe(transName))
                .put("artistIds", albumArtistIds)
                .put("company", safe(company))
                .put("publishTime", publishTime)
                .put("language", safe(language))
                .put("songTags", albumTagArray)
                .put("production", safe(production))
                .put("albumSongDiscs", songArray)
                .put("source", safe(source))
                .put("notes", safe(notes))
                .put("albumSongDiscVoList", songArray);
        if (rawCover != null) {
            vo.put("albumRawNosKey", rawCover.nosKey());
        }
        if (squareCover != null) {
            vo.put("album1v1NosKey", squareCover.nosKey());
        }
        return vo;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
