package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「我的贡献」列表条目模型。
 *
 * <p>字段与原 APK 的 {@code Lt1/e} 一一对应（数据源 {@code /api/rep/ugc/detail} →
 * {@code data.wikiDetailResultVoList[]}）：
 * <ul>
 *   <li>{@code name}        内容名称（原版经 {@code Lo/g;->m} 空安全取值）</li>
 *   <li>{@code bizId}       业务 ID（歌曲 / 专辑 / 艺人 / 歌词对应的主键）</li>
 *   <li>{@code createTime}  提交时间（毫秒）</li>
 *   <li>{@code status}      审核状态，取值 0 / 1 / 4 / 5 / -5</li>
 *   <li>{@code artists}     或 {@code artistSimpleVos}，原版用 {@code " / "} 连接为 {@code artistName}</li>
 *   <li>{@code approveNames} / {@code rejectNames} 参与审核通过 / 驳回的用户昵称</li>
 * </ul>
 */
public class Contribution {

    /** 状态：0 待审核 / 1 审核中 / 4 部分通过 / 5 审核通过 / -5 未采纳 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_AUDITING = 1;
    public static final int STATUS_PARTIAL = 4;
    public static final int STATUS_APPROVED = 5;
    public static final int STATUS_REJECTED = -5;

    /** 与原 APK {@code Lt1/P;->e} 一致的状态名，用于空状态 / 通知文案 */
    public static String statusName(int status) {
        switch (status) {
            case STATUS_PENDING:
                return "待审核";
            case STATUS_AUDITING:
                return "审核中";
            case STATUS_PARTIAL:
                return "部分通过";
            case STATUS_APPROVED:
                return "审核通过";
            case STATUS_REJECTED:
                return "未采纳";
            default:
                return "未知状态";
        }
    }

    public String name = "";
    public String artistName = "";
    public String coverUrl = "";
    public String targetId = "";
    public int type;
    public int status;
    public long createTime;

    /**
     * 服务端记录主键（{@code id}）。实测歌词（5）/ 翻译（6）查询返回的条目该字段为空，
     * 因此它不能单独作为唯一键，仅作为 {@link #stableKey()} 的首选来源。
     */
    public String serverId = "";

    /**
     * 该条目来自哪一类查询（取值见 {@link NetEaseWikiClient#TYPE_VALUES}）。
     *
     * <p>实测：歌词（5）与翻译（6）条目的 {@code type} 字段恒为 {@code 0}，与歌曲 / 专辑 /
     * 艺人条目无法靠 {@code type} 区分，也不足以区分「歌词」与「翻译」。因此由数据层在
     * 解析时按「来源查询类型」补齐该字段，供卡片类型标签与跳转分发使用。
     */
    public int sourceType = -1;
    public final List<String> approveNames = new ArrayList<>();
    public final List<String> rejectNames = new ArrayList<>();

    private static final String[] KEY_NAME = {"name", "songName", "title", "targetName"};

    /**
     * 封面可能出现的键名（原版列表接口未固定字段名，做多键兼容）。
     *
     * <p>实测 {@code /api/rep/ugc/detail} 返回的封面字段名为 {@code nosKey}
     * （值形如 {@code http://p2.music.126.net/xxx.jpg}），此前未收录导致所有卡片都取不到封面。
     */
    private static final String[] KEY_COVER = {
            "nosKey", "coverUrl", "picUrl", "coverImgUrl", "imgUrl", "blurPicUrl",
            "albumPicUrl", "albumCoverUrl", "resourceCoverUrl"
    };

    /** 相对路径（如 {@code yyimgs/xx.jpg}）补全为网易云 CDN 绝对地址 */
    private static final String CDN_PREFIX = "https://p1.music.126.net/";

    /** 封面可能内嵌在子对象中（歌曲 → 专辑封面 / 资源对象） */
    private static final String[][] NESTED_COVER = {
            {"song", "album", "picUrl"},
            {"album", "picUrl"},
            {"resource", "picUrl"},
            {"resource", "coverUrl"},
            {"res", "picUrl"}
    };

    public static Contribution fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        Contribution c = new Contribution();
        c.name = firstString(o, KEY_NAME);
        c.targetId = String.valueOf(o.optLong("bizId", 0L));
        c.serverId = firstString(o, new String[]{"id"});
        c.createTime = o.optLong("createTime", 0L);
        c.status = o.optInt("status", Integer.MIN_VALUE);
        c.type = o.optInt("type", -1);
        c.artistName = joinArtistNames(o);
        c.coverUrl = resolveCover(o);
        c.approveNames.addAll(nameList(o.optJSONArray("approveNames")));
        c.rejectNames.addAll(nameList(o.optJSONArray("rejectNames")));
        return c;
    }

    /** 优先取平铺封面键，缺失时逐层下钻子对象；都没有则返回空串 */
    private static String resolveCover(JSONObject o) {
        String direct = firstString(o, KEY_COVER);
        if (!direct.isEmpty()) {
            return normalizeCover(direct);
        }
        for (String[] path : NESTED_COVER) {
            JSONObject cur = o;
            String value = "";
            for (int i = 0; i < path.length; i++) {
                if (cur == null) {
                    break;
                }
                if (i == path.length - 1) {
                    value = cur.optString(path[i], "");
                } else {
                    cur = cur.optJSONObject(path[i]);
                }
            }
            if (value != null && !value.trim().isEmpty() && !"null".equals(value.trim())) {
                return normalizeCover(value.trim());
            }
        }
        return "";
    }

    /** 相对路径补全为 CDN 绝对地址，保证后续域名白名单校验可通过 */
    private static String normalizeCover(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            return "";
        }
        String lower = u.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://")) {
            // 网易 CDN 全站支持 HTTPS；targetSdk 28+ 默认禁止明文流量，
            // 继续用 http 会被系统直接拦截，表现为封面始终停在占位图。
            if (lower.contains(".music.126.net") || lower.contains(".126.net")) {
                return "https://" + u.substring("http://".length());
            }
            return u;
        }
        if (lower.startsWith("https://")) {
            return u;
        }
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        return CDN_PREFIX + (u.startsWith("/") ? u.substring(1) : u);
    }

    /** 批量解析，跳过非法条目（不因单条脏数据导致整页失败） */
    public static List<Contribution> listFrom(JSONArray arr) {
        List<Contribution> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (int i = 0; i < arr.length(); i++) {
            try {
                Contribution c = fromJson(arr.optJSONObject(i));
                if (c != null) {
                    list.add(c);
                }
            } catch (Exception e) {
                // 单条脏数据只跳过该条并记录，绝不让整页加载失败
                android.util.Log.w("Contribution", "第 " + i + " 条解析失败，已跳过", e);
            }
        }
        return list;
    }

    /** 展示用标题，空值兜底 */
    public String displayName() {
        return name == null || name.trim().isEmpty() ? "未命名内容" : name.trim();
    }

    /** 展示用歌手，空值兜底 */
    public String displayArtist() {
        return artistName == null ? "" : artistName.trim();
    }

    /**
     * 跳转分发用的类型：优先取条目自身的 {@code type}，缺失（歌词 / 翻译与部分脏数据为 0）
     * 时回落到「来源查询类型」。
     *
     * <p>这样「全部」标签页的卡片也能按条目真实类型生成外链，而不是一律落到单曲页。
     */
    public int routeType() {
        return type > 0 ? type : sourceType;
    }

    /**
     * 列表判等的稳定主键。
     *
     * <p>原实现直接用 {@code bizId} 作主键，同一首歌曲的「歌词」与「翻译」共用同一
     * {@code bizId} 且 {@code type} 都是 0，会被 {@code DiffUtil} 判成同一条而互相覆盖，
     * 表现为两者在列表中「只剩一条 / 不显示」。此处改用服务端记录主键；歌词 / 翻译没有该
     * 字段时，退化为「bizId + 提交时间 + 名称」组合键（歌词与翻译的提交时间不同，可稳定区分）。
     *
     * <p>组合键刻意不含 {@link #sourceType}：同一条歌词可能同时出现在「全部」与「歌词」
     * 两个来源的查询结果里，只有键完全一致才能在聚合去重时合并成一条。
     */
    public String stableKey() {
        if (serverId != null && !serverId.isEmpty()) {
            return serverId;
        }
        return targetId + "#" + createTime + "#" + displayName();
    }

    /** 状态语义（供 UI 映射颜色与文案） */
    public Status statusEnum() {
        switch (status) {
            case STATUS_PENDING:
                return Status.PENDING;
            case STATUS_AUDITING:
                return Status.AUDITING;
            case STATUS_PARTIAL:
                return Status.PARTIAL;
            case STATUS_APPROVED:
                return Status.APPROVED;
            case STATUS_REJECTED:
                return Status.REJECTED;
            default:
                return Status.UNKNOWN;
        }
    }

    public enum Status {
        PENDING, AUDITING, PARTIAL, APPROVED, REJECTED, UNKNOWN
    }

    // ------------------------------------------------------------------

    /**
     * 原版优先取 {@code artists}，为空时退到 {@code artistSimpleVos}，
     * 每个元素取名称后用 {@code " / "} 连接。
     */
    private static String joinArtistNames(JSONObject o) {
        JSONArray arr = o.optJSONArray("artists");
        if (arr == null) {
            arr = o.optJSONArray("artistSimpleVos");
        }
        if (arr == null) {
            return firstString(o, new String[]{"artistName", "artist", "singer"});
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            String name = itemName(item);
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** 数组元素可能是字符串，也可能是含 name / artistName 的对象。 */
    private static String itemName(Object item) {
        if (item == null || item == JSONObject.NULL) {
            return "";
        }
        if (item instanceof JSONObject) {
            JSONObject obj = (JSONObject) item;
            return firstString(obj, new String[]{"name", "artistName", "nickname", "userName"});
        }
        String s = String.valueOf(item).trim();
        return "null".equals(s) ? "" : s;
    }

    private static List<String> nameList(JSONArray arr) {
        if (arr == null || arr.length() == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            String s = itemName(arr.opt(i));
            if (s.isEmpty()) {
                s = String.valueOf(arr.opt(i)).trim();
            }
            if (!s.isEmpty() && !"null".equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static String firstString(JSONObject o, String[] keys) {
        for (String k : keys) {
            if (!o.has(k)) {
                continue;
            }
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equals(s)) {
                return s;
            }
        }
        return "";
    }
}
