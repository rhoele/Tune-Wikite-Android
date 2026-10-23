package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 查询历史（纯逻辑 + JSON 序列化，对应基准 F4）。
 *
 * 条目契约与基准保持一致：{@code input / id / json / scroll_y}，
 * 保证从基准版本迁移过来的历史数据可直接复用。
 */
public final class QueryHistory {

    /** 最多保留的历史条数，超出后淘汰最旧记录 */
    public static final int MAX_ENTRIES = 20;

    public static class Entry {
        /** 用户原始输入 */
        public String input = "";
        /** 归一化后的歌曲 ID */
        public long id;
        /** 完整 JSON（用于离线回看） */
        public String json = "";
        /** 上次浏览的滚动位置 */
        public int scrollY;

        public Entry() {
        }

        public Entry(String input, long id, String json, int scrollY) {
            this.input = input == null ? "" : input;
            this.id = id;
            this.json = json == null ? "" : json;
            this.scrollY = scrollY;
        }

        public String displayTitle() {
            if (input != null && !input.trim().isEmpty()) {
                return input.trim();
            }
            return id > 0 ? String.valueOf(id) : "";
        }
    }

    private QueryHistory() {
    }

    /**
     * 追加一条历史：若同 ID 已存在则先移除旧记录（最近使用上浮），
     * 再插入到列表头部，并裁剪到 {@link #MAX_ENTRIES}。
     *
     * @return 新列表（不修改入参）
     */
    public static List<Entry> push(List<Entry> current, Entry entry) {
        List<Entry> result = new ArrayList<>();
        if (entry != null && (entry.id > 0 || !entry.displayTitle().isEmpty())) {
            result.add(entry);
        }
        if (current != null) {
            for (Entry e : current) {
                if (e == null) {
                    continue;
                }
                boolean sameId = entry != null && entry.id > 0 && e.id == entry.id;
                boolean sameInput = entry != null
                        && !entry.displayTitle().isEmpty()
                        && entry.displayTitle().equals(e.displayTitle());
                if (sameId || sameInput) {
                    continue;
                }
                result.add(e);
            }
        }
        if (result.size() > MAX_ENTRIES) {
            return new ArrayList<>(result.subList(0, MAX_ENTRIES));
        }
        return result;
    }

    /** 序列化为 JSON 数组文本，供 SharedPreferences 持久化。 */
    public static String toJson(List<Entry> entries) {
        JSONArray arr = new JSONArray();
        if (entries != null) {
            for (Entry e : entries) {
                if (e == null) {
                    continue;
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("input", e.input);
                    o.put("id", e.id);
                    o.put("json", e.json);
                    o.put("scroll_y", e.scrollY);
                } catch (Exception ignored) {
                    // JSONObject.put 在 UTF-8 下不会失败，此处仅防御性兜底
                }
                arr.put(o);
            }
        }
        return arr.toString();
    }

    /** 反序列化；非法输入返回空列表，绝不抛异常（保证启动不崩）。 */
    public static List<Entry> fromJson(String text) {
        List<Entry> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.input = o.optString("input", "");
                e.id = o.optLong("id");
                e.json = o.optString("json", "");
                e.scrollY = o.optInt("scroll_y", 0);
                if (e.id > 0 || !e.input.isEmpty()) {
                    result.add(e);
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    /** 只读视图，防止外部误改内部结构。 */
    public static List<Entry> unmodifiable(List<Entry> entries) {
        return entries == null ? Collections.emptyList() : Collections.unmodifiableList(entries);
    }
}
