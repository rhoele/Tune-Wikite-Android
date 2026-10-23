package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * 查询历史的持久化封装（对应基准 F4）。
 * 存储介质与键名与基准一致，便于历史数据无损迁移。
 */
public class QueryHistoryStore {

    private static final String PREFS_NAME = "public_wiki_queries";
    private static final String KEY_ENTRIES = "entries";

    private final SharedPreferences sp;

    public QueryHistoryStore(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<QueryHistory.Entry> load() {
        return QueryHistory.fromJson(sp.getString(KEY_ENTRIES, null));
    }

    /** 追加一条记录（同 ID 去重并上浮到最近） */
    public void push(QueryHistory.Entry entry) {
        List<QueryHistory.Entry> next = QueryHistory.push(load(), entry);
        sp.edit().putString(KEY_ENTRIES, QueryHistory.toJson(next)).apply();
    }

    /** 最近一次查询，无历史返回 null */
    public QueryHistory.Entry last() {
        List<QueryHistory.Entry> list = load();
        return list.isEmpty() ? null : list.get(0);
    }

    /** 记录滚动位置，用于下次进入时恢复（F14） */
    public void updateScrollY(long songId, int scrollY) {
        List<QueryHistory.Entry> list = load();
        if (list.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (QueryHistory.Entry e : list) {
            if (e.id == songId) {
                e.scrollY = scrollY;
                changed = true;
                break;
            }
        }
        if (!changed) {
            return;
        }
        sp.edit().putString(KEY_ENTRIES, QueryHistory.toJson(list)).apply();
    }

    public void clear() {
        sp.edit().remove(KEY_ENTRIES).apply();
    }
}
