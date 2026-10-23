package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌曲百科数据模型（对应基准 F5/F6/F7）。
 * 解析遵循「宽容读取 + 兜底默认值」原则：任一字段缺失都不应导致整页失败，
 * 仅降级为「未知歌曲 / 匿名贡献者 / 时间未知」等兜底文案（对应 G19）。
 */
public class SongWiki {

    // ---- 基础信息 ----
    public long songId;
    public String songName = "";
    public final List<String> artistNames = new ArrayList<>();
    public String coverUrl = "";
    public String lang = "";

    // ---- ugc 区块 ----
    public String coverType = "";
    public String duration = "";
    public String transName = "";
    public final List<String> locks = new ArrayList<>();

    // ---- wiki 区块 ----
    public String bpm = "";
    public String language = "";
    public String releaseDate = "";
    public String releaseVersion = "";
    public final List<String> genres = new ArrayList<>();
    public final List<String> moods = new ArrayList<>();
    public final List<String> entertainment = new ArrayList<>();
    public final List<Creator> creators = new ArrayList<>();

    // ---- 贡献记录 ----
    public final List<Record> records = new ArrayList<>();

    /** 原始 JSON 文本，用于「复制完整 JSON」（F9）与历史持久化（F4）。 */
    public String rawJson = "";

    /** 创作角色分组，如「作词」→ [黄家驹] */
    public static class Creator {
        public String role = "";
        public final List<Person> people = new ArrayList<>();
    }

    /** 创作者个人 */
    public static class Person {
        public String artistId = "";
        public String name = "";
    }

    /** 公开贡献记录 */
    public static class Record {
        public String nickName = "";
        public long userId;
        public long createTime;
        public final List<String> approveNames = new ArrayList<>();
        public String userImg = "";
    }

    /** 展示用歌名，空值兜底 */
    public String displayName() {
        return songName == null || songName.trim().isEmpty() ? "未知歌曲" : songName;
    }

    /** 展示用歌手名（多人以 / 连接） */
    public String displayArtists() {
        if (artistNames.isEmpty()) {
            return "未知歌手";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < artistNames.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            String n = artistNames.get(i);
            sb.append(n == null || n.trim().isEmpty() ? "未知歌手" : n.trim());
        }
        return sb.toString();
    }

    /** 歌手名以空格连接，供剪贴板/单行展示 */
    public String displayArtistsInline() {
        if (artistNames.isEmpty()) {
            return "未知歌手";
        }
        return String.join("/", artistNames);
    }

    /**
     * 解析聚合接口返回的整包 JSON。
     *
     * @param body 原始响应体
     * @throws WikiException 当 JSON 非法或业务数据缺失时抛出对应类别异常
     */
    public static SongWiki fromJson(String body) throws WikiException {
        if (body == null || body.trim().isEmpty()) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON", e);
        }

        // 业务开关：{ "ok": false } 或缺失 data 均视为无数据
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new WikiException(WikiException.Kind.EMPTY, "未返回歌曲数据");
        }

        SongWiki w = new SongWiki();
        w.rawJson = body;
        w.songId = data.optLong("songId");
        w.songName = optStringOr(data, "songName", "");
        w.coverUrl = optStringOr(data, "coverUrl", "");
        w.lang = optStringOr(data, "lang", "");
        fillStringList(w.artistNames, data.optJSONArray("artistName"));

        JSONObject ugc = data.optJSONObject("ugc");
        if (ugc != null) {
            w.coverType = optStringOr(ugc, "coverType", "");
            w.duration = optStringOr(ugc, "duration", "");
            w.transName = optStringOr(ugc, "transName", "");
            fillStringList(w.locks, ugc.optJSONArray("locks"));
        }

        JSONObject wiki = data.optJSONObject("wiki");
        if (wiki != null) {
            w.bpm = optStringOr(wiki, "bpm", "");
            w.language = optStringOr(wiki, "language", "");
            w.releaseDate = optStringOr(wiki, "releaseDate", "");
            w.releaseVersion = optStringOr(wiki, "releaseVersion", "");
            fillStringList(w.genres, wiki.optJSONArray("genres"));
            fillStringList(w.moods, wiki.optJSONArray("moods"));
            fillStringList(w.entertainment, wiki.optJSONArray("entertainment"));
            fillCreators(w.creators, wiki.optJSONArray("creators"));
        }

        fillRecords(w.records, data.optJSONArray("records"));

        // 业务空数据：既无名称也无任何资料
        boolean hasContent = w.songId > 0
                || !w.songName.isEmpty()
                || !w.artistNames.isEmpty()
                || !w.creators.isEmpty()
                || !w.genres.isEmpty();
        if (!hasContent) {
            throw new WikiException(WikiException.Kind.EMPTY, "空的歌曲数据");
        }
        return w;
    }

    // ------------------------------------------------------------------
    // 解析辅助
    // ------------------------------------------------------------------

    private static void fillStringList(List<String> target, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).trim();
            if (!s.isEmpty() && !"null".equals(s)) {
                target.add(s);
            }
        }
    }

    private static void fillCreators(List<Creator> target, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Creator c = new Creator();
            c.role = optStringOr(o, "role", "");
            JSONArray people = o.optJSONArray("people");
            if (people != null) {
                for (int j = 0; j < people.length(); j++) {
                    JSONObject p = people.optJSONObject(j);
                    if (p == null) {
                        continue;
                    }
                    Person person = new Person();
                    person.artistId = optStringOr(p, "artistId", "");
                    person.name = optStringOr(p, "name", "");
                    c.people.add(person);
                }
            }
            target.add(c);
        }
    }

    private static void fillRecords(List<Record> target, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Record r = new Record();
            r.nickName = optStringOr(o, "nickName", "");
            r.userId = o.optLong("userId");
            r.createTime = o.optLong("createTime");
            r.userImg = optStringOr(o, "userImg", "");
            JSONArray approve = o.optJSONArray("approveNames");
            if (approve == null) {
                // 兼容单值写法
                String single = optStringOr(o, "approveName", "");
                if (!single.isEmpty()) {
                    r.approveNames.add(single);
                }
            } else {
                fillStringList(r.approveNames, approve);
            }
            target.add(r);
        }
    }

    /** 宽容读取：兼容字符串/数字/布尔，缺失返回默认值。 */
    private static String optStringOr(JSONObject o, String key, String def) {
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equals(s) ? def : s;
    }

    /** 只读的歌手名列表，供外部安全遍历。 */
    public List<String> artistNamesView() {
        return Collections.unmodifiableList(artistNames);
    }
}
