package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用内「审核消息」收件箱。
 *
 * <p>负责把贡献列表中<b>已出审核结果</b>的条目聚合为未读消息：
 * <ul>
 *   <li>首次运行只「播种」状态快照，不产生未读，避免历史条目轰炸；</li>
 *   <li>之后只要某条目的审核状态发生变化（或新条目直接出了结果），就写入未读；</li>
 *   <li>未读在用户点开该条目或点「全部已读」后消失，重启应用仍保留。</li>
 * </ul>
 *
 * <p>数据全部落在 {@link WikiPrefs}，前台刷新与后台轮询共用同一份状态，
 * 因此无论应用是否在前台，审核结果都能被聚合到顶栏入口。
 */
final class AuditInbox {

    private static final String KEY_ITEMS = "audit_inbox_items_v1";
    private static final String KEY_SNAPSHOT = "audit_status_snapshot_v1";
    private static final String KEY_INITIALIZED = "audit_inbox_initialized_v1";
    /**
     * 收件箱首次建立快照的时间。
     *
     * <p>用于判定「快照里没有的条目」是新结果还是历史遗留：只有提交时间晚于该时点的
     * 条目才生成未读。否则首次轮询分批覆盖不同类型时，后一批的历史条目会被全部当成
     * 新消息，造成一次性轰炸。
     */
    private static final String KEY_INIT_AT = "audit_inbox_init_at_v1";
    private static final int MAX_ITEMS = 50;

    /** 未读集合变化监听：主线程回调，供页面即时刷新角标与排序 */
    interface Listener {
        void onInboxChanged();
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AuditInbox() {
    }

    static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * 清空收件箱、快照与去重基准。
     *
     * <p>仅在「用户主动退出登录 / 切换账号」时调用：这些数据是账号维度的，
     * 留给下一个账号会暴露上一位用户看到过的审核结果。
     */
    static void clearAll(Context context) {
        WikiPrefs.get(context).edit()
                .remove(KEY_ITEMS)
                .remove(KEY_SNAPSHOT)
                .remove(KEY_INITIALIZED)
                .remove(KEY_INIT_AT)
                .apply();
        notifyChanged();
    }

    /** 在后台线程写入后统一切回主线程通知页面，避免跨线程刷新 UI。 */
    private static void notifyChanged() {
        if (LISTENERS.isEmpty()) {
            return;
        }
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                try {
                    listener.onInboxChanged();
                } catch (Exception ignored) {
                    // 单个页面的刷新异常不影响其它监听者
                }
            }
        });
    }

    static final class Entry {
        final String key;
        final String name;
        final String artist;
        final int status;
        final long time;
        /**
         * 条目所属查询类型（{@link NetEaseWikiClient#TYPE_VALUES}）。
         *
         * <p>0 表示未知：点开消息时按全部类型扫描。知道类型时只翻一个分类，
         * 定位「较久远的历史贡献」不会因为要扫六个分类而变慢。
         */
        final int type;

        Entry(String key, String name, String artist, int status, long time) {
            this(key, name, artist, status, time, 0);
        }

        Entry(String key, String name, String artist, int status, long time, int type) {
            this.key = key;
            this.name = name;
            this.artist = artist;
            this.status = status;
            this.time = time;
            this.type = type;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("key", key);
                o.put("name", name);
                o.put("artist", artist);
                o.put("status", status);
                o.put("time", time);
                o.put("type", type);
            } catch (Exception ignored) {
                // 字段均为非空字符串 / 基本类型，不会失败
            }
            return o;
        }

        static Entry fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            return new Entry(o.optString("key", ""), o.optString("name", ""),
                    o.optString("artist", ""), o.optInt("status", Integer.MIN_VALUE),
                    o.optLong("time", 0L), o.optInt("type", 0));
        }
    }

    /**
     * 用一批贡献条目刷新审核消息。
     *
     * <p>可被前台加载与后台轮询重复调用：只有「状态发生变化的已出结果条目」才会
     * 生成未读，重复调用不会重复计数。
     */
    static synchronized void observe(Context context, List<Contribution> items) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        SharedPreferences sp = WikiPrefs.get(context);
        JSONObject snapshot = readObject(sp.getString(KEY_SNAPSHOT, ""));
        boolean initialized = sp.getBoolean(KEY_INITIALIZED, false);
        long initAt = sp.getLong(KEY_INIT_AT, 0L);
        List<Entry> entries = readEntries(sp.getString(KEY_ITEMS, ""));
        boolean changed = false;
        boolean unreadAdded = false;

        // 旧版本升级：快照已初始化但没有初始化时点，补记为当前时间。
        // 否则 createTime >= 0 恒成立，会把全部历史结果重新标成未读。
        if (initialized && initAt <= 0L) {
            initAt = System.currentTimeMillis();
            sp.edit().putLong(KEY_INIT_AT, initAt).commit();
        }

        // 首次运行：只记录现状不产生未读，避免历史结果被当成新消息。
        // 注意待审核 / 审核中的条目也要落快照，否则它们出结果时快照里没有旧状态，
        // 无法判定为「状态变化」，这正是此前收件箱永远为空、卡片永远没有「新」标记的根因。
        if (!initialized) {
            for (Contribution item : items) {
                if (item == null) {
                    continue;
                }
                String key = item.stableKey();
                if (TextUtils.isEmpty(key)) {
                    continue;
                }
                try {
                    snapshot.put(key, item.status);
                } catch (Exception ignored) {
                    // JSONObject.put(String, int) 不会失败
                }
            }
            sp.edit().putBoolean(KEY_INITIALIZED, true)
                    .putLong(KEY_INIT_AT, System.currentTimeMillis())
                    .putString(KEY_SNAPSHOT, snapshot.toString())
                    .commit();
            return;
        }

        for (Contribution item : items) {
            if (item == null) {
                continue;
            }
            String key = item.stableKey();
            if (TextUtils.isEmpty(key)) {
                continue;
            }
            int previous = snapshot.optInt(key, Integer.MIN_VALUE);
            boolean unseen = previous == Integer.MIN_VALUE;
            boolean hasResult = isResultStatus(item.statusEnum());
            // 已记录过状态 → 状态变化即为新结果；
            // 从未记录过 → 只有当它是「收件箱建立之后提交的」才算新结果，
            // 这样分批覆盖不同类型时不会把历史条目误判成新消息。
            boolean isNewResult = hasResult
                    && (unseen ? (item.createTime > 0 && item.createTime >= initAt)
                    : previous != item.status);
            if (isNewResult) {
                // 同一条结果可能先由推送写入（无稳定键），再被轮询发现：合并为一条
                removePushDuplicates(entries, item.displayName(), item.status);
                upsert(entries, new Entry(key, item.displayName(), item.displayArtist(),
                        item.status, item.createTime, queryTypeOf(item)));
                changed = true;
                unreadAdded = true;
            }
            if (previous != item.status) {
                try {
                    snapshot.put(key, item.status);
                } catch (Exception ignored) {
                    // JSONObject.put(String, int) 不会失败
                }
                changed = true;
            }
        }

        if (changed) {
            sp.edit().putString(KEY_SNAPSHOT, snapshot.toString())
                    .putString(KEY_ITEMS, toJson(entries).toString())
                    .commit();
        }
        if (unreadAdded) {
            notifyChanged();
        }
    }

    /** 只有这三种状态才算「出了审核结果」，待审核 / 审核中不生成消息。 */
    private static boolean isResultStatus(Contribution.Status status) {
        return status == Contribution.Status.APPROVED
                || status == Contribution.Status.REJECTED
                || status == Contribution.Status.PARTIAL;
    }

    /**
     * 取条目的查询类型；取值不在 {@link NetEaseWikiClient#TYPE_VALUES} 里时返回 0（未知）。
     *
     * <p>跳转时按该类型查询能直接命中分类页，不必把六个分类都翻一遍。
     */
    static int queryTypeOf(Contribution item) {
        int type = item.routeType();
        for (int value : NetEaseWikiClient.TYPE_VALUES) {
            if (value == type) {
                return type;
            }
        }
        return 0;
    }

    /**
     * 由系统推送（FCM）直接写入一条未读审核消息。
     *
     * <p>推送在开启提醒开关后即为最快的到达通道；落库后页面无需重新打开即可刷新角标。
     */
    static synchronized void addPushEntry(Context context, String key, String name,
                                          String artist, int status, long time) {
        if (context == null) {
            return;
        }
        SharedPreferences sp = WikiPrefs.get(context);
        List<Entry> entries = readEntries(sp.getString(KEY_ITEMS, ""));
        String safeName = name == null ? "" : name;
        String safeKey = TextUtils.isEmpty(key)
                ? "push:" + System.currentTimeMillis() : key.trim();
        if (TextUtils.isEmpty(key)) {
            // 服务端未给稳定键时按「名称 + 状态」去重，避免重复推送让角标虚增
            for (Entry entry : entries) {
                if (entry.status == status && safeName.equals(entry.name)) {
                    return;
                }
            }
        }
        upsert(entries, new Entry(safeKey, safeName,
                artist == null ? "" : artist, status,
                time > 0 ? time : System.currentTimeMillis()));
        sp.edit().putString(KEY_ITEMS, toJson(entries).toString()).commit();
        notifyChanged();
    }

    /** 轮询拿到真实条目后，清掉此前由推送写入的同名同状态占位，避免重复计数。 */
    private static void removePushDuplicates(List<Entry> entries, String name, int status) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.key != null && entry.key.startsWith("push:")
                    && entry.status == status && TextUtils.equals(entry.name, name)) {
                entries.remove(i);
            }
        }
    }

    static synchronized Set<String> unreadKeys(Context context) {
        Set<String> keys = new HashSet<>();
        for (Entry entry : entries(context)) {
            if (!TextUtils.isEmpty(entry.key)) {
                keys.add(entry.key);
            }
        }
        return keys;
    }

    static synchronized int unreadCount(Context context) {
        return entries(context).size();
    }

    /**
     * 已存在未读消息的「名称 + 状态」指纹。
     *
     * <p>用于轮询去重：推送已经提醒过的结果，下一轮轮询不必再弹一次系统通知。
     */
    static synchronized Set<String> deliveredFingerprints(Context context) {
        Set<String> out = new HashSet<>();
        for (Entry entry : entries(context)) {
            out.add(entry.name + "|" + entry.status);
        }
        return out;
    }

    static synchronized List<Entry> entries(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        return readEntries(WikiPrefs.get(context).getString(KEY_ITEMS, ""));
    }

    static synchronized void markRead(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) {
            return;
        }
        SharedPreferences sp = WikiPrefs.get(context);
        List<Entry> entries = readEntries(sp.getString(KEY_ITEMS, ""));
        boolean removed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (key.equals(entries.get(i).key)) {
                entries.remove(i);
                removed = true;
            }
        }
        if (removed) {
            sp.edit().putString(KEY_ITEMS, toJson(entries).toString()).apply();
            notifyChanged();
        }
    }

    static synchronized void markAllRead(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences sp = WikiPrefs.get(context);
        boolean had = !readEntries(sp.getString(KEY_ITEMS, "")).isEmpty();
        sp.edit().remove(KEY_ITEMS).apply();
        if (had) {
            notifyChanged();
        }
    }

    // ------------------------------------------------------------------

    private static void upsert(List<Entry> entries, Entry entry) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entry.key.equals(entries.get(i).key)) {
                entries.remove(i);
            }
        }
        entries.add(0, entry);
        while (entries.size() > MAX_ITEMS) {
            entries.remove(entries.size() - 1);
        }
    }

    private static JSONObject readObject(String raw) {
        try {
            return TextUtils.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static List<Entry> readEntries(String raw) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = TextUtils.isEmpty(raw) ? null : new JSONArray(raw);
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                Entry entry = Entry.fromJson(arr.optJSONObject(i));
                if (entry != null && !TextUtils.isEmpty(entry.key)) {
                    out.add(entry);
                }
            }
        } catch (Exception ignored) {
            // 数据损坏时按空收件箱处理
        }
        return out;
    }

    private static JSONArray toJson(List<Entry> entries) {
        JSONArray arr = new JSONArray();
        for (Entry entry : entries) {
            arr.put(entry.toJson());
        }
        return arr;
    }
}
