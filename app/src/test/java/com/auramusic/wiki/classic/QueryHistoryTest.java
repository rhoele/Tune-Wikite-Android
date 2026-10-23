package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询历史（去重 / 排序 / 上限 / 序列化）的单测（覆盖 G6）。
 */
public class QueryHistoryTest {

    private static QueryHistory.Entry entry(String input, long id) {
        return new QueryHistory.Entry(input, id, "{\"data\":{\"songId\":" + id + "}}", 0);
    }

    @Test
    public void pushInsertsToFront() {
        List<QueryHistory.Entry> list = new ArrayList<>();
        list = QueryHistory.push(list, entry("111", 111));
        list = QueryHistory.push(list, entry("222", 222));
        assertEquals(2, list.size());
        assertEquals("222", list.get(0).displayTitle());
        assertEquals("111", list.get(1).displayTitle());
    }

    @Test
    public void pushDeduplicatesSameId() {
        List<QueryHistory.Entry> list = new ArrayList<>();
        list = QueryHistory.push(list, entry("111", 111));
        list = QueryHistory.push(list, entry("222", 222));
        list = QueryHistory.push(list, entry("111", 111));
        assertEquals(2, list.size());
        assertEquals("111", list.get(0).displayTitle());
    }

    @Test
    public void pushCapsAtMaxEntries() {
        List<QueryHistory.Entry> list = new ArrayList<>();
        for (int i = 1; i <= QueryHistory.MAX_ENTRIES + 5; i++) {
            list = QueryHistory.push(list, entry(String.valueOf(i), i));
        }
        assertEquals(QueryHistory.MAX_ENTRIES, list.size());
        assertEquals(String.valueOf(QueryHistory.MAX_ENTRIES + 5), list.get(0).displayTitle());
    }

    @Test
    public void pushIgnoresNullEntry() {
        List<QueryHistory.Entry> list = new ArrayList<>();
        list = QueryHistory.push(list, entry("111", 111));
        list = QueryHistory.push(list, null);
        assertEquals(1, list.size());
    }

    @Test
    public void jsonRoundTripPreservesFields() {
        List<QueryHistory.Entry> list = new ArrayList<>();
        list = QueryHistory.push(list, new QueryHistory.Entry("347230", 347230L,
                "{\"a\":1}", 480));

        String json = QueryHistory.toJson(list);
        List<QueryHistory.Entry> restored = QueryHistory.fromJson(json);

        assertEquals(1, restored.size());
        assertEquals("347230", restored.get(0).input);
        assertEquals(347230L, restored.get(0).id);
        assertEquals("{\"a\":1}", restored.get(0).json);
        assertEquals(480, restored.get(0).scrollY);
    }

    @Test
    public void malformedJsonYieldsEmptyListNotNull() {
        assertTrue(QueryHistory.fromJson("not json at all").isEmpty());
        assertTrue(QueryHistory.fromJson("").isEmpty());
        assertTrue(QueryHistory.fromJson(null).isEmpty());
        assertTrue(QueryHistory.fromJson("[{},{},]").isEmpty());
    }

    @Test
    public void entryWithoutInputFallsBackToId() {
        QueryHistory.Entry e = new QueryHistory.Entry("", 999L, "", 0);
        assertEquals("999", e.displayTitle());
    }

    @Test
    public void unmodifiableHandlesNull() {
        assertTrue(QueryHistory.unmodifiable(null).isEmpty());
    }
}
