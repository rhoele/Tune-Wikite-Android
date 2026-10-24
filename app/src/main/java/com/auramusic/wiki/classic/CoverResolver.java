package com.auramusic.wiki.classic;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贡献卡片封面补齐。
 *
 * <p>列表接口对不同类型返回的封面字段并不一致：歌词 / 翻译条目通常完全没有封面字段，
 * 单曲 / 专辑 / 艺人分类查询也可能与「全部」的结构不同，于是同一张卡片会出现
 * 「全部」有封面、切到分类页却没有封面的现象。这里统一按 {@code bizId} 走公开详情端点补齐：
 * <ul>
 *   <li>歌曲 / 歌词 / 翻译 → 所属歌曲的专辑封面；</li>
 *   <li>专辑 / 补充专辑 → 专辑封面；</li>
 *   <li>艺人 → 艺人头像。</li>
 * </ul>
 *
 * <p>只补齐缺失的封面，已有 {@code nosKey} 的条目保持原样；结果按「类型 + ID」缓存在内存，
 * 同一页来回切换不会重复请求。
 */
final class CoverResolver {

    interface Callback {
        /** @param filled 本次实际补齐的条目数；0 表示无需或无法补齐 */
        void onResolved(int filled);
    }

    private static final String TAG = "CoverResolver";

    private static final String SONG_DETAIL =
            "https://music.163.com/api/song/detail/?ids=%s";
    private static final String ALBUM_DETAIL = "https://music.163.com/api/v1/album/%s";
    private static final String ARTIST_DETAIL = "https://music.163.com/api/artist/%s";

    /** 单次最多补齐的条目数：一页 20 条，留一倍余量，避免异常数据放大请求量 */
    private static final int MAX_ENTRIES = 40;

    private static final String KIND_SONG = "song";
    private static final String KIND_ALBUM = "album";
    private static final String KIND_ARTIST = "artist";

    private static volatile CoverResolver instance;

    private final LruCache<String, String> cache = new LruCache<>(512);
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    static CoverResolver get() {
        if (instance == null) {
            synchronized (CoverResolver.class) {
                if (instance == null) {
                    instance = new CoverResolver();
                }
            }
        }
        return instance;
    }

    private CoverResolver() {
    }

    /**
     * 为缺少封面的条目补齐封面；完成后在主线程回调。
     *
     * @param items 当前展示的条目（与列表持有的是同一批对象）
     */
    void resolve(final List<Contribution> items, final Callback callback) {
        if (items == null || items.isEmpty()) {
            notifyCallback(callback, 0);
            return;
        }
        final Map<String, List<Contribution>> groups = new LinkedHashMap<>();
        final Set<String> songIds = new LinkedHashSet<>();
        int prefilled = 0;
        for (Contribution item : items) {
            if (item == null) {
                continue;
            }
            // 只有「补充专辑」的 nosKey 是用户上传的真实封面；歌曲 / 专辑 / 艺人
            // 列表接口返回的往往是网易云的默认灰底音符占位图，歌词 / 翻译则完全没有封面。
            // 因此除补充专辑外，一律按 bizId 取实体封面覆盖，保证同一首歌在各标签页一致。
            if (item.routeType() == NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT
                    && CoverLoader.isAllowed(item.coverUrl)) {
                continue;
            }
            String id = item.targetId == null ? "" : item.targetId.trim();
            // 只接受纯数字 ID：脏数据（0 / 空 / 非数字）直接跳过，避免拼出无效 URL
            if (id.isEmpty() || !id.matches("\\d{1,19}") || "0".equals(id)) {
                continue;
            }
            String key = kindOf(item.routeType()) + ":" + id;
            String cached = cache.get(key);
            if (cached != null) {
                item.coverUrl = cached;
                prefilled++;
                continue;
            }
            List<Contribution> bucket = groups.get(key);
            if (bucket == null) {
                if (groups.size() >= MAX_ENTRIES) {
                    continue;
                }
                bucket = new ArrayList<>(2);
                groups.put(key, bucket);
            }
            bucket.add(item);
            if (key.startsWith(KIND_SONG + ":")) {
                songIds.add(id);
            }
        }
        final int prefilledCount = prefilled;
        if (groups.isEmpty()) {
            // 全部命中内存缓存时也要回传补齐数量：否则调用方会以为「没有变化」而不刷新列表
            notifyCallback(callback, prefilled);
            return;
        }

        int albumOrArtistTasks = 0;
        for (String key : groups.keySet()) {
            if (!key.startsWith(KIND_SONG + ":")) {
                albumOrArtistTasks++;
            }
        }
        final int totalTasks = (songIds.isEmpty() ? 0 : 1) + albumOrArtistTasks;
        final AtomicInteger remaining = new AtomicInteger(totalTasks);
        final Map<String, String> resolved = Collections.synchronizedMap(new LinkedHashMap<>());

        if (!songIds.isEmpty()) {
            pool.execute(() -> {
                try {
                    Map<String, String> found = fetchSongCovers(songIds);
                    resolved.putAll(found);
                } catch (Exception e) {
                    Log.w(TAG, "补歌曲封面失败（" + songIds.size() + " 条），保留原占位", e);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        apply(groups, resolved, prefilledCount, callback);
                    }
                }
            });
        }
        for (final String key : groups.keySet()) {
            if (key.startsWith(KIND_SONG + ":")) {
                continue;
            }
            pool.execute(() -> {
                try {
                    String id = key.substring(key.indexOf(':') + 1);
                    String url = KIND_ALBUM.equals(kindOfKey(key))
                            ? fetchAlbumCover(id) : fetchArtistCover(id);
                    if (url != null && !url.isEmpty()) {
                        resolved.put(key, url);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "补封面失败 " + key, e);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        apply(groups, resolved, prefilledCount, callback);
                    }
                }
            });
        }
    }

    /** 把补齐结果写回条目（主线程），再回调调用方刷新列表。 */
    private void apply(final Map<String, List<Contribution>> groups,
                       final Map<String, String> resolved, final int prefilled,
                       final Callback callback) {
        main.post(() -> {
            int filled = prefilled;
            for (Map.Entry<String, List<Contribution>> entry : groups.entrySet()) {
                String url = resolved.get(entry.getKey());
                if (url == null || url.isEmpty() || !CoverLoader.isAllowed(url)) {
                    continue;
                }
                cache.put(entry.getKey(), url);
                for (Contribution item : entry.getValue()) {
                    item.coverUrl = url;
                    filled++;
                }
            }
            if (callback != null) {
                callback.onResolved(filled);
            }
        });
    }

    private static void notifyCallback(final Callback callback, final int filled) {
        if (callback != null) {
            callback.onResolved(filled);
        }
    }

    private static String kindOf(int routeType) {
        switch (routeType) {
            case NetEaseWikiClient.TYPE_ALBUM:
            case NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT:
                return KIND_ALBUM;
            case NetEaseWikiClient.TYPE_ARTIST:
                return KIND_ARTIST;
            default:
                // 歌曲、歌词、翻译以及未知类型都按“所属歌曲”取专辑封面
                return KIND_SONG;
        }
    }

    private static String kindOfKey(String key) {
        return key.substring(0, key.indexOf(':'));
    }

    // ------------------------------------------------------------------
    // 网络请求与解析（解析函数抽成包级可见，便于单测）
    // ------------------------------------------------------------------

    private static Map<String, String> fetchSongCovers(Set<String> ids) throws Exception {
        StringBuilder array = new StringBuilder("[");
        for (String id : ids) {
            if (array.length() > 1) {
                array.append(',');
            }
            array.append(id);
        }
        array.append(']');
        String url = String.format(Locale.US, SONG_DETAIL,
                URLEncoder.encode(array.toString(), "UTF-8"));
        String body = Http.get(url, NetEaseWeApi.UA, NetEaseWeApi.REFERER, null, null);
        Map<String, String> out = new LinkedHashMap<>();
        parseSongCovers(body, out);
        return out;
    }

    private static String fetchAlbumCover(String albumId) throws Exception {
        String body = Http.get(String.format(Locale.US, ALBUM_DETAIL, albumId),
                NetEaseWeApi.UA, NetEaseWeApi.REFERER, null, null);
        return parseAlbumCover(body);
    }

    private static String fetchArtistCover(String artistId) throws Exception {
        String body = Http.get(String.format(Locale.US, ARTIST_DETAIL, artistId),
                NetEaseWeApi.UA, NetEaseWeApi.REFERER, null, null);
        return parseArtistCover(body);
    }

    /** 解析 {@code /api/song/detail} 响应：id → 专辑封面。 */
    static void parseSongCovers(String json, Map<String, String> out) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray songs = root.optJSONArray("songs");
        if (songs == null) {
            return;
        }
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) {
                continue;
            }
            JSONObject album = song.optJSONObject("album");
            String pic = album == null ? "" : album.optString("picUrl", "");
            if (pic != null && !pic.trim().isEmpty()) {
                out.put(KIND_SONG + ":" + song.optLong("id", 0L), pic.trim());
            }
        }
    }

    /** 解析 {@code /api/v1/album/{id}} 响应。 */
    static String parseAlbumCover(String json) throws Exception {
        JSONObject album = new JSONObject(json).optJSONObject("album");
        return album == null ? "" : album.optString("picUrl", "").trim();
    }

    /** 解析 {@code /api/artist/{id}} 响应，缺 {@code picUrl} 时退回 {@code img1v1Url}。 */
    static String parseArtistCover(String json) throws Exception {
        JSONObject artist = new JSONObject(json).optJSONObject("artist");
        if (artist == null) {
            return "";
        }
        String pic = artist.optString("picUrl", "").trim();
        if (pic.isEmpty()) {
            pic = artist.optString("img1v1Url", "").trim();
        }
        return pic;
    }
}
