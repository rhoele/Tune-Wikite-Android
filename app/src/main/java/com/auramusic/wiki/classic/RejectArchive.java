package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 未通过原因归档。
 *
 * <p>审核未通过的具体原因只写在「曲库小学妹」的私信里。本类在出现未通过结果时
 * 拉取一次私信（{@link NetEaseMsgClient}），把每条通知解析成
 * 「专辑 + 原因 + 原文」存进本地，按原因分组后供页面浏览与复制。
 *
 * <p>去重按「消息时间 + 原文哈希」，因此重复同步不会让条目翻倍；
 * 拉取频率限制为 {@link #SYNC_INTERVAL_MS}，避免每次刷新列表都打一次私信接口。
 */
final class RejectArchive {

    private static final String TAG = "RejectArchive";

    private static final String KEY_ITEMS = "reject_archive_items_v1";
    private static final String KEY_SYNC_AT = "reject_archive_sync_at_v1";
    /** 是否已完成过一次「整段聊天记录」的回填 */
    private static final String KEY_FULL_DONE = "reject_archive_full_done_v1";
    /** 上次浏览归档页的时间：晚于它的条目算「新」，用于 +N 标识 */
    private static final String KEY_SEEN_AT = "reject_archive_seen_at_v1";
    /**
     * 解析规则版本。
     *
     * <p>升级后视为「尚未完成全量回填」，强制再扫一遍历史：
     * 新增支持的歌词 / 翻译 / 纠错通知只会在全量扫描时被补进归档。
     */
    private static final String KEY_PARSE_VERSION = "reject_archive_parse_version";
    private static final int PARSE_VERSION = 3;

    /**
     * 清空归档数据（保留解析规则版本）。
     *
     * <p>仅在「用户主动退出登录 / 切换账号」时调用：归档来自该账号的审核私信，
     * 跨账号保留会把上一位用户的未通过原因展示给下一位用户。
     */
    static void clearAll(Context context) {
        WikiPrefs.get(context).edit()
                .remove(KEY_ITEMS)
                .remove(KEY_SYNC_AT)
                .remove(KEY_FULL_DONE)
                .remove(KEY_SEEN_AT)
                .apply();
    }

    /** 项目类型：专辑补充 */
    static final int CAT_ALBUM = 0;
    /** 项目类型：纠错 · 专辑 */
    static final int CAT_FIX_ALBUM = 1;
    /** 项目类型：纠错 · 单曲 */
    static final int CAT_FIX_SONG = 2;
    /** 项目类型：纠错 · 艺人 */
    static final int CAT_FIX_ARTIST = 3;
    /** 项目类型：纠错（未细分） */
    static final int CAT_FIX = 4;
    /** 项目类型：纠错 · 歌词（不在「我的贡献」生成条目，仅归档） */
    static final int CAT_FIX_LYRIC = 5;
    /** 项目类型：纠错 · 翻译（不在「我的贡献」生成条目，仅归档） */
    static final int CAT_FIX_TRANSLATION = 6;
    /** 项目类型：歌词 */
    static final int CAT_LYRIC = 7;
    /** 项目类型：翻译 */
    static final int CAT_TRANSLATION = 8;
    /** 项目类型：其它 */
    static final int CAT_OTHER = 9;

    /** 两次自动同步的最小间隔 */
    private static final long SYNC_INTERVAL_MS = 10 * 60 * 1000L;
    /** 归档上限：按「全量聊天记录」的口径放宽，避免早期原因被新记录挤掉 */
    private static final int MAX_ITEMS = 2000;

    /** 专辑名：《...》 */
    private static final Pattern ALBUM = Pattern.compile("《([^》]*)》");
    /**
     * 「您提交的专辑《…》未…」。
     *
     * <p>专辑名本身可能带书名号（如「DOUDOU《忘记了也没关系》Orchestral House Remix」），
     * 非贪婪的 {@link #ALBUM} 会在内层书名号处截断，这里用贪婪匹配一直取到「》未」之前。
     */
    private static final Pattern SUBMITTED_ALBUM =
            Pattern.compile("您提交的[^《]{0,8}《(.+)》未");
    /** 原因里的并列项目：（歌曲列表/歌曲名/封面） */
    private static final Pattern PAREN_ITEMS = Pattern.compile("[（(]([^）)]{1,60})[）)]");
    /** 「由于 ... 未通过审核」型原因（歌词 / 翻译通知） */
    private static final Pattern DUE_TO = Pattern.compile("由于\\s*(.+?)(?:未通过|未审核)");
    /** 「由于：……，因此没有被选用」型原因（纠错落选通知） */
    private static final Pattern DUE_TO_LOST = Pattern.compile("由于\\s*[：:]\\s*(.+?)(?:，因此|。|$)");
    /** 「原因」标记：全角 / 半角冒号、拒绝 / 驳回两种措辞 */
    private static final String[] REASON_MARKS = {
            "拒绝原因：", "拒绝原因:", "驳回原因：", "驳回原因:",
    };
    /** 通知落款：新老文案不同（咨询 / 私信联系 / 联系） */
    private static final String[] NOTICE_TAILS = {
            "咨询@曲库小学妹", "私信联系@曲库小学妹", "联系@曲库小学妹",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RejectArchive() {
    }

    /** 一条已归档的未通过通知。 */
    static final class Entry {
        final String album;
        final String reason;
        /** 通知原文，复制时保留完整措辞 */
        final String raw;
        final long time;
        /** 项目类型（{@link #CAT_*}），由原文推导，不落库 */
        final int category;

        Entry(String album, String reason, String raw, long time) {
            this.album = album;
            this.reason = reason;
            this.raw = raw;
            this.time = time;
            this.category = categoryOf(raw);
        }

        String id() {
            return time + "|" + (raw == null ? "" : raw.hashCode());
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("album", album);
                o.put("reason", reason);
                o.put("raw", raw);
                o.put("time", time);
            } catch (Exception ignored) {
                // 字段均为字符串 / 基本类型，不会失败
            }
            return o;
        }

        static Entry fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            String reason = o.optString("reason", "");
            if (TextUtils.isEmpty(reason)) {
                return null;
            }
            return new Entry(o.optString("album", ""), reason,
                    o.optString("raw", ""), o.optLong("time", 0L));
        }
    }

    /** 同一项目类型 + 同一原因的归档集合。 */
    static final class Group {
        final String reason;
        final int category;
        final List<Entry> items = new ArrayList<>();

        Group(String reason, int category) {
            this.reason = reason;
            this.category = category;
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 解析结果缓存。
     *
     * <p>归档原文是一整段 JSON（数百条时数十 KB），解析一次约 90ms；
     * 而设置页摘要与归档页都会反复读取，缓存后除首次外基本零成本。
     * 原文变化（同步写入）时自动失效。
     */
    private static final Object CACHE_LOCK = new Object();
    private static String cachedRaw;
    private static List<Entry> cachedEntries = Collections.emptyList();

    static List<Entry> entries(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        final String raw = WikiPrefs.get(context).getString(KEY_ITEMS, "");
        synchronized (CACHE_LOCK) {
            if (raw.equals(cachedRaw)) {
                return cachedEntries;
            }
        }
        List<Entry> parsed = read(raw);
        synchronized (CACHE_LOCK) {
            cachedRaw = raw;
            cachedEntries = parsed;
        }
        return parsed;
    }

    /** 按项目类型 + 原因分组：先专辑补充，再纠错（专辑 / 单曲 / 艺人），最后歌词、翻译。 */
    static List<Group> groups(Context context) {
        List<Entry> all = entries(context);
        Map<String, Group> index = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        for (Entry entry : all) {
            String key = entry.category + "|" + groupKey(entry.reason);
            Group target = index.get(key);
            if (target == null) {
                target = new Group(entry.reason, entry.category);
                index.put(key, target);
                groups.add(target);
            }
            target.items.add(entry);
        }
        Collections.sort(groups, (a, b) -> {
            if (a.category != b.category) {
                return Integer.compare(a.category, b.category);
            }
            return Long.compare(latest(b), latest(a));
        });
        return groups;
    }

    private static long latest(Group group) {
        long max = 0L;
        for (Entry entry : group.items) {
            max = Math.max(max, entry.time);
        }
        return max;
    }

    static void clear(Context context) {
        if (context == null) {
            return;
        }
        WikiPrefs.get(context).edit().remove(KEY_ITEMS).apply();
        synchronized (CACHE_LOCK) {
            cachedRaw = null;
            cachedEntries = Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /**
     * 按需同步审核私信（限频）。
     *
     * <p>私信是「未通过原因」的唯一来源，其中歌词 / 翻译纠错根本不会在贡献列表
     * 生成条目，因此不能要求列表里先出现未通过结果才去同步 —— 否则这类结果
     * 只能等用户手动进入归档页才补得回来。现在前台刷新与后台轮询都会按
     * {@link #SYNC_INTERVAL_MS} 的节奏补一次。
     */
    static void syncIfNeeded(Context context) {
        if (context == null) {
            return;
        }
        if (!SessionManager.isLoggedIn(context)) {
            return;
        }
        if (System.currentTimeMillis() - lastSyncAt(context) < SYNC_INTERVAL_MS) {
            return;
        }
        syncNow(context, !fullBackfillDone(context), null);
    }

    /**
     * 立即拉取一次私信并归档。
     *
     * @param fullHistory 是否翻完整个会话（历史回填）；{@code false} 只取最新一页
     * @param onDone      主线程回调，参数为「是否有新条目」以及失败原因
     *                    （成功时为 {@code null}）；可为 {@code null}
     */
    static void syncNow(Context context, boolean fullHistory, SyncCallback onDone) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final boolean full = fullHistory;
        new Thread(() -> {
            boolean added = false;
            String error = null;
            try {
                Log.d(TAG, "同步审核私信 full=" + full);
                List<NetEaseMsgClient.Notice> notices =
                        NetEaseMsgClient.latestNotices(app, null, full);
                // 全量回填（首次 / 解析规则升级）会一次补进大量历史记录，不能再逐条推送，
                // 否则用户会收到几十条「未采纳」通知。
                added = archive(app, notices, !full);
                // 只有真正拿到响应才记录同步时间：失败时若也写入时间戳，
                // 10 分钟的节流会挡住紧随其后的重试，页面会长时间停在「没有新内容」。
                markSynced(app);
                if (full) {
                    markFullDone(app);
                }
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "同步审核私信失败：" + error, e);
            }
            final boolean changed = added;
            final String message = error;
            MAIN.post(() -> {
                if (onDone != null) {
                    onDone.onFinished(changed, message);
                }
            });
        }, "reject-archive").start();
    }

    /** 解析通知并合并进归档；返回是否有新条目。 */
    private static synchronized boolean archive(Context context,
                                                List<NetEaseMsgClient.Notice> notices,
                                                boolean notify) {
        if (notices == null || notices.isEmpty()) {
            return false;
        }
        SharedPreferences sp = WikiPrefs.get(context);
        List<Entry> existing = read(sp.getString(KEY_ITEMS, ""));
        Set<String> ids = new HashSet<>();
        for (Entry entry : existing) {
            ids.add(entry.id());
        }
        List<Entry> merged = new ArrayList<>(existing);
        List<Entry> fresh = new ArrayList<>();
        boolean added = false;
        for (NetEaseMsgClient.Notice notice : notices) {
            Entry entry = parse(notice);
            if (entry == null || ids.contains(entry.id())) {
                continue;
            }
            ids.add(entry.id());
            merged.add(entry);
            fresh.add(entry);
            added = true;
        }
        if (!added) {
            return false;
        }
        Collections.sort(merged, (a, b) -> Long.compare(b.time, a.time));
        while (merged.size() > MAX_ITEMS) {
            merged.remove(merged.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (Entry entry : merged) {
            arr.put(entry.toJson());
        }
        sp.edit().putString(KEY_ITEMS, arr.toString()).apply();
        // 歌词 / 翻译纠错不会在「我的贡献」生成条目，审核状态推送覆盖不到，
        // 归档到新通知时补一条系统提醒，避免只能在打开归档页时才发现。
        if (notify) {
            notifyTextCorrections(context, fresh);
        }
        return true;
    }

    /** 为新归档的歌词 / 翻译纠错补发系统通知（其余类型由审核结果推送覆盖）。 */
    private static void notifyTextCorrections(Context context, List<Entry> fresh) {
        if (!WikiPrefs.backgroundAuditEnabled(context)) {
            return;
        }
        // 长时间离线后一次补进多条时只提醒最新的几条，避免通知栏被刷屏
        fresh.sort((a, b) -> Long.compare(b.time, a.time));
        int posted = 0;
        for (Entry entry : fresh) {
            if (!isTextCorrection(entry)) {
                continue;
            }
            NotificationHelper.postAuditResult(context, displayName(entry),
                    Contribution.Status.REJECTED);
            if (++posted >= 3) {
                break;
            }
        }
    }

    /**
     * 把一条私信解析成归档条目。
     *
     * <p>审核通知有两种写法：带「拒绝原因：」前缀的标准格式，
     * 以及直接跟在「咨询@曲库小学妹。」后面的自然语句（如「歌名过长」）。
     * 两种都要能取到原因；`null` 之类的空原因直接丢弃。
     */
    static Entry parse(NetEaseMsgClient.Notice notice) {
        if (notice == null || TextUtils.isEmpty(notice.text)) {
            return null;
        }
        String text = notice.text.replace("\\n", " ").replace("\n", " ").trim();
        // 审核通知的措辞会随业务线变化（审核 / 未通过 / 驳回），只要命中其一就继续解析；
        // 真正决定是否入库的是下面能否取到非空原因。
        if (!text.contains("未通过") && !text.contains("未审核")
                && !text.contains("驳回") && !text.contains("拒绝原因")
                && !text.contains("驳回原因") && !text.contains("没有被选用")) {
            return null;
        }
        String album = albumOf(text);
        String reason = extractReason(text);
        if (reason.isEmpty()) {
            return null;
        }
        return new Entry(album, reason, text, notice.time);
    }

    /**
     * 从通知原文里抽取「未通过原因」。
     *
     * <p>实测存在四类写法，按可靠性依次尝试：
     * <ol>
     *   <li>显式的「拒绝原因：/ 驳回原因：」；</li>
     *   <li>歌词 / 翻译通知的「……的歌词由于 XXX 未通过审核」；</li>
     *   <li>「……联系@曲库小学妹。补充说明」——落款后那段才是原因；</li>
     *   <li>纠错通知的通用措辞「因有误差或需以官方信息为准」。</li>
     * </ol>
     */
    private static String extractReason(String text) {
        // 1. 显式标记
        int mark = -1;
        int markLength = 0;
        for (String candidate : REASON_MARKS) {
            int index = text.indexOf(candidate);
            if (index >= 0 && (mark < 0 || index < mark)) {
                mark = index;
                markLength = candidate.length();
            }
        }
        if (mark >= 0) {
            String value = clean(text.substring(mark + markLength));
            if (!value.isEmpty() && !boilerplate(value)) {
                return value;
            }
        }
        // 2. 「由于 …… 未通过审核」
        Matcher due = DUE_TO.matcher(text);
        if (due.find()) {
            String value = clean(due.group(1));
            if (!value.isEmpty() && !boilerplate(value)) {
                return value;
            }
        }
        // 2.5「由于：……，因此没有被选用」（纠错落选，没有「未通过」字样）
        Matcher lost = DUE_TO_LOST.matcher(text);
        if (lost.find()) {
            String value = clean(lost.group(1));
            if (!value.isEmpty() && !boilerplate(value)) {
                return value;
            }
        }
        // 3. 落款之后的补充说明
        int tail = -1;
        int tailLength = 0;
        for (String candidate : NOTICE_TAILS) {
            int index = text.lastIndexOf(candidate);
            if (index > tail) {
                tail = index;
                tailLength = candidate.length();
            }
        }
        if (tail >= 0) {
            int dot = text.indexOf('。', tail + tailLength);
            String value = clean(dot >= 0 ? text.substring(dot + 1)
                    : text.substring(tail + tailLength));
            if (!value.isEmpty() && !boilerplate(value)) {
                return value;
            }
        }
        // 4. 纠错类通用措辞
        if (text.contains("因有误差或需以官方信息为准")) {
            return "资料有误差或需以官方信息为准";
        }
        return "";
    }

    /** 是否为「感谢贡献」这类落款话术，不能当作原因。 */
    private static boolean boilerplate(String value) {
        if (value == null) {
            return true;
        }
        String v = value.trim();
        return v.isEmpty() || v.matches("^(依然)?感谢您的?贡献[。！!]?$");
    }

    /** 去掉首尾空白与残留的 `null`，并把连续空白压成单个空格。 */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace('\u00a0', ' ').trim();
        value = value.replaceAll("^[：:，,。\\s]+", "");
        // 「由于 X，未通过审核」这类断句会在尾部留下逗号，原因本身不带它
        value = value.replaceAll("[，,；;：:\\s]+$", "");
        value = value.replaceAll("[\\s]*null[\\s]*$", "").trim();
        return value.replaceAll("\\s{2,}", " ");
    }

    private static String groupKey(String reason) {
        return clean(reason);
    }

    // ------------------------------------------------------------------
    // 通知目标（用于没有专辑名的条目）
    // ------------------------------------------------------------------

    /** 「您对艺人X的纠错修改」「您对专辑《Y》的纠错修改」 */
    private static final Pattern TARGET_PATTERN = Pattern.compile(
            "您对(艺人|专辑|歌曲|歌词|翻译歌词)?(?:《(.+)》|([^的]{1,80}?))的(?:纠错)?修改");
    /** 「您提交的专辑《X》未被审核通过」 */
    private static final Pattern SUBMITTED_PATTERN = Pattern.compile(
            "您提交的(艺人|专辑|歌曲|歌词|翻译歌词)?《([^》]*)》");

    /**
     * 「您对艺人X的纠错修改」中不带书名号的对象名。
     *
     * <p>纠错通知里专辑 / 艺人名未必写成《…》，复制卡片时需要单独清空，
     * 否则会残留第一条通知的具体名称。已带书名号（清空后为《》）的情况不会被匹配。
     */
    private static final Pattern CORRECTION_TARGET = Pattern.compile(
            "(您对(?:艺人|专辑|歌曲|单曲|歌词翻译|翻译歌词|歌词|翻译))([^《》的]{1,80}?)(的(?:纠错)?修改)");

    /** 去掉零宽字符与首尾空白（部分通知的专辑名是零宽空格） */
    private static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replaceAll("[\\u200B-\\u200F\\uFEFF]", "").trim();
        return value.replaceAll("\\s{2,}", " ");
    }

    /** 纯 Java 的空串判断（单测里 android.text.TextUtils 是桩实现，不能依赖） */
    private static boolean blank(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * 从通知原文解析被审核的对象。
     *
     * @return {@code {类型, 名称}}（类型可能为空串）；解析不到返回 {@code null}
     */
    @Nullable
    static String[] targetOf(String raw) {
        if (blank(raw)) {
            return null;
        }
        Matcher matcher = TARGET_PATTERN.matcher(raw);
        if (matcher.find()) {
            String type = matcher.group(1) == null ? "" : matcher.group(1);
            String name = cleanName(matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
            if (!blank(name)) {
                return new String[]{type, name};
            }
        }
        Matcher submitted = SUBMITTED_PATTERN.matcher(raw);
        if (submitted.find()) {
            String type = submitted.group(1) == null ? "" : submitted.group(1);
            String name = cleanName(submitted.group(2));
            if (!blank(name)) {
                return new String[]{type, name};
            }
        }
        return null;
    }

    /**
     * 从通知原文推导被审核对象的名称。
     *
     * <p>优先「您提交的专辑《…》未…」这一最强上下文（可正确处理名称里嵌套的书名号），
     * 其次是「您对专辑X的纠错修改」这种不带书名号的写法，最后才退回通用的书名号匹配。
     * 全部从原文推导，因此旧归档条目不需要迁移也能得到正确的名称。
     */
    private static String albumOf(String raw) {
        if (blank(raw)) {
            return "";
        }
        Matcher submitted = SUBMITTED_ALBUM.matcher(raw);
        if (submitted.find()) {
            String name = cleanName(submitted.group(1));
            if (!blank(name)) {
                return name;
            }
        }
        String[] target = targetOf(raw);
        if (target != null && "专辑".equals(target[0])) {
            return target[1];
        }
        Matcher matcher = ALBUM.matcher(raw);
        if (matcher.find()) {
            String name = cleanName(matcher.group(1));
            if (!blank(name)) {
                return name;
            }
        }
        return "";
    }

    /**
     * 条目在列表里的名称。
     *
     * <p>归档里有一批通知并不属于专辑（艺人纠错、歌词纠错等），
     * 以前统一显示「未注明专辑」是错的；这里优先用解析出的目标名。
     */
    static String displayName(Entry entry) {
        if (entry == null) {
            return "";
        }
        String album = albumOf(entry.raw);
        if (!blank(album)) {
            return album;
        }
        String stored = cleanName(entry.album);
        if (!blank(stored)) {
            return stored;
        }
        String[] target = targetOf(entry.raw);
        if (target != null) {
            return target[1];
        }
        return "—";
    }

    /**
     * 判断通知属于哪类项目：专辑补充 / 纠错（专辑、单曲、艺人）/ 歌词 / 翻译 / 其它。
     *
     * <p>由原文措辞推导，因此老数据无需迁移即可获得分类。
     */
    static int categoryOf(String raw) {
        if (raw == null || raw.isEmpty()) {
            return CAT_OTHER;
        }
        if (raw.startsWith("您提交的专辑")) {
            return CAT_ALBUM;
        }
        if (raw.startsWith("您对专辑")) {
            return CAT_FIX_ALBUM;
        }
        if (raw.startsWith("您对艺人")) {
            return CAT_FIX_ARTIST;
        }
        if (raw.startsWith("您对单曲") || raw.startsWith("您对歌曲")) {
            return CAT_FIX_SONG;
        }
        if (raw.startsWith("您对歌词翻译") || raw.startsWith("您对翻译")) {
            return CAT_FIX_TRANSLATION;
        }
        if (raw.startsWith("您对歌词")) {
            return CAT_FIX_LYRIC;
        }
        if (raw.startsWith("您对")) {
            return CAT_FIX;
        }
        // 歌词 / 翻译纠错即使措辞变化也归到对应的纠错类型（这两类不会在贡献列表生成条目）
        if (raw.contains("纠错")) {
            if (raw.contains("歌词翻译") || raw.contains("翻译")) {
                return CAT_FIX_TRANSLATION;
            }
            if (raw.contains("歌词")) {
                return CAT_FIX_LYRIC;
            }
        }
        if (raw.startsWith("您纠错的")) {
            return CAT_FIX;
        }
        if (raw.startsWith("你上传的") || raw.startsWith("您上传的")) {
            int index = raw.indexOf("》的");
            if (index > 0) {
                String kind = raw.substring(index + 2);
                if (kind.startsWith("翻译") || kind.startsWith("歌词翻译")) {
                    return CAT_TRANSLATION;
                }
                if (kind.startsWith("歌词")) {
                    return CAT_LYRIC;
                }
                if (kind.startsWith("专辑")) {
                    return CAT_ALBUM;
                }
            }
            return CAT_OTHER;
        }
        if (raw.startsWith("您反馈的")) {
            if (raw.contains("翻译")) {
                return CAT_TRANSLATION;
            }
            if (raw.contains("歌词") || raw.contains("时间轴")) {
                return CAT_LYRIC;
            }
            return CAT_FIX;
        }
        if (raw.contains("歌词翻译")) {
            return CAT_TRANSLATION;
        }
        if (raw.contains("歌词")) {
            return CAT_LYRIC;
        }
        return CAT_OTHER;
    }

    /** 歌词 / 翻译纠错不会在「我的贡献」里生成条目，只能用于复制归档。 */
    static boolean isTextCorrection(Entry entry) {
        if (entry == null || blank(entry.raw)) {
            return false;
        }
        return entry.raw.startsWith("您对歌词") || entry.raw.startsWith("您对翻译");
    }

    /**
     * 长按跳转用的目标名称。
     *
     * <p>跳转依赖「贡献列表里的名称与通知里的名称一致」，因此专辑、歌曲（歌词 / 翻译的
     * 书名号内容）、艺人纠错都要返回名称；歌词 / 翻译纠错没有对应贡献条目，
     * 无法解析出名称时才视为不可跳转。
     */
    static String jumpTarget(Entry entry) {
        if (entry == null || isTextCorrection(entry)) {
            return "";
        }
        String album = albumOf(entry.raw);
        if (!blank(album)) {
            return album;
        }
        String stored = cleanName(entry.album);
        if (!blank(stored)) {
            return stored;
        }
        String[] target = targetOf(entry.raw);
        return target == null ? "" : target[1];
    }

    /**
     * 复制卡片的「完整原文」：保留通知句式，只把具体对象名清空。
     *
     * <p>卡片是同一类原因的聚合，复制时不应带上任何一部专辑 / 艺人名：
     * <ul>
     *   <li>书名号里的名称（含 {@code 《A《B》C》} 这种嵌套写法）清空为 {@code 《》}；</li>
     *   <li>「您对艺人 X 的纠错修改」这类不带书名号的写法，同样把 X 清空。</li>
     * </ul>
     */
    static String rawWithoutAlbum(String raw) {
        if (blank(raw)) {
            return "";
        }
        String text = blankBookTitles(raw);
        return CORRECTION_TARGET.matcher(text).replaceAll("$1$3");
    }

    /**
     * 把书名号内的内容清空（保留一对书名号）。
     *
     * <p>专辑名里可能再嵌套书名号（{@code 《DOUDOU《忘记了也没关系》Remix》}），
     * 非贪婪正则会在内层 {@code 》} 处截断并残留后半段，所以这里按括号深度扫描。
     * 书名号不配对时原样返回，避免把整段通知吞掉。
     */
    private static String blankBookTitles(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '《') {
                depth++;
                // 嵌套书名号只保留最外层的一对
                if (depth == 1) {
                    out.append(c);
                }
            } else if (c == '》') {
                if (depth == 1) {
                    out.append(c);
                }
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                out.append(c);
            }
        }
        return depth == 0 ? out.toString() : raw;
    }

    /** 距今是否已经超过自动同步间隔（用于进入归档页时静默补一次增量）。 */
    static boolean syncDue(Context context) {
        return context != null
                && System.currentTimeMillis() - lastSyncAt(context) >= SYNC_INTERVAL_MS;
    }

    // ------------------------------------------------------------------
    // 存储
    // ------------------------------------------------------------------

    private static long lastSyncAt(Context context) {
        return WikiPrefs.get(context).getLong(KEY_SYNC_AT, 0L);
    }

    private static void markSynced(Context context) {
        WikiPrefs.get(context).edit()
                .putLong(KEY_SYNC_AT, System.currentTimeMillis()).apply();
    }

    /** 是否已经完成过一次整段会话的回填（解析规则升级后需要重扫） */
    static boolean fullBackfillDone(Context context) {
        if (context == null) {
            return false;
        }
        return WikiPrefs.get(context).getBoolean(KEY_FULL_DONE, false)
                && WikiPrefs.get(context).getInt(KEY_PARSE_VERSION, 0) >= PARSE_VERSION;
    }

    private static void markFullDone(Context context) {
        WikiPrefs.get(context).edit()
                .putBoolean(KEY_FULL_DONE, true)
                .putInt(KEY_PARSE_VERSION, PARSE_VERSION)
                .apply();
    }

    /** 上次浏览归档页的时间（{@code 0} 表示还没记录过） */
    static long seenAt(Context context) {
        return context == null ? 0L : WikiPrefs.get(context).getLong(KEY_SEEN_AT, 0L);
    }

    /** 离开归档页时把「已看到这里」记录下来，下次进来 +N 与高亮随之清空 */
    static void markSeen(Context context) {
        if (context == null) {
            return;
        }
        WikiPrefs.get(context).edit()
                .putLong(KEY_SEEN_AT, System.currentTimeMillis()).apply();
    }

    private static List<Entry> read(String raw) {
        List<Entry> stored = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return stored;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                Entry entry = Entry.fromJson(arr.optJSONObject(i));
                if (entry != null) {
                    stored.add(entry);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "归档数据损坏，按空列表处理", e);
        }
        // 原因里带「（A/B/C）」的混合条目在读取时拆成多条：老数据不需要迁移，
        // 每条只对应一个项目，复制原因时也不会串到别的项目上。
        List<Entry> out = new ArrayList<>(stored.size());
        for (Entry entry : stored) {
            List<String> variants = splitReason(entry.reason);
            if (variants.size() <= 1) {
                out.add(entry);
                continue;
            }
            for (String variant : variants) {
                out.add(new Entry(entry.album, variant, entry.raw, entry.time));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** 混合项目原因（如「提供资料与添加内容（歌曲列表/歌曲名/封面）不一致」）按项目拆开。 */
    static List<String> splitReason(String reason) {
        List<String> out = new ArrayList<>(1);
        if (reason == null || reason.isEmpty()) {
            return out;
        }
        Matcher matcher = PAREN_ITEMS.matcher(reason);
        if (!matcher.find()) {
            out.add(reason);
            return out;
        }
        String[] parts = matcher.group(1).split("[/／]");
        if (parts.length < 2) {
            out.add(reason);
            return out;
        }
        for (String part : parts) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            out.add(reason.substring(0, matcher.start(1)) + item + reason.substring(matcher.end(1)));
        }
        if (out.isEmpty()) {
            out.add(reason);
        }
        return out;
    }

    /** 同步完成回调（主线程）。 */
    interface SyncCallback {
        /**
         * @param changed 是否归档到新条目
         * @param error   失败原因；成功时为 {@code null}
         */
        void onFinished(boolean changed, String error);
    }
}
