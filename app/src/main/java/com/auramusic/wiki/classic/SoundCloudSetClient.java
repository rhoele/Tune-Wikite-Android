package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SoundCloud set（播放列表）解析器。
 *
 * <p>SoundCloud 的下载接口只处理单曲，因此 set 需要先从页面里取出曲目清单，再逐首走
 * {@link SoundCloudApiClient#download}。SoundCloud 会在页面内嵌
 * {@code window.__sc_hydration} JSON，其中 {@code hydratable=playlist} 的
 * {@code data.tracks} 就是<b>按播放列表顺序</b>排列的曲目。
 *
 * <p>两个实际踩到的坑：
 * <ul>
 *   <li><b>m.soundcloud.com</b> 返回的是另一套移动端页面，完全没有
 *       {@code __sc_hydration}，必须先归一化为 {@code soundcloud.com} 再抓取；</li>
 *   <li>页面/接口为了省流量，最多只内联前 5 首的完整信息，其余条目只给
 *       {@code {id, kind, policy}} 占位。这类占位不是「曲目不存在」，
 *       需要用 {@code api-v2/tracks?ids=...} 批量补全。</li>
 * </ul>
 */
final class SoundCloudSetClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 "
            + "like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
            + "Mobile/15E148 Safari/604.1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /** 批量补全曲目时单次请求的 id 数量 */
    private static final int HYDRATE_BATCH = 50;

    private static final java.util.regex.Pattern ANCHOR =
            java.util.regex.Pattern.compile("<a\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern HREF =
            java.util.regex.Pattern.compile("href=\"([^\"]*)\"");
    private static final java.util.regex.Pattern ARIA_LABEL =
            java.util.regex.Pattern.compile("aria-label=\"([^\"]*)\"");

    private SoundCloudSetClient() {
    }

    static final class Track {
        final String title;
        final String artist;
        final String url;
        final long durationMs;
        /**
         * SoundCloud 只提供 30 秒试听片段（{@code policy=SNIP}）。
         *
         * <p>这类曲目所有转码都是 {@code snipped}，必须先识别出来，
         * 否则整张专辑会得到一串无意义的下载失败。
         */
        boolean previewOnly;

        Track(String title, String artist, String url, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.url = url;
            this.durationMs = durationMs;
        }
    }

    static final class Set {
        final String title;
        final String artist;
        final String artworkUrl;
        final String url;
        final List<Track> tracks;
        /** SoundCloud 声明的曲目总数（含不可用占位） */
        final int totalCount;
        /** 确实无法下载的曲目数量（删除 / 私密 / 弃权等） */
        final int unavailableCount;
        /** 只能试听（policy=SNIP）的曲目数量 */
        final int previewCount;

        Set(String title, String artist, String artworkUrl, String url, List<Track> tracks,
            int totalCount, int unavailableCount) {
            this.title = title;
            this.artist = artist;
            this.artworkUrl = artworkUrl;
            this.url = url;
            this.tracks = tracks;
            this.totalCount = totalCount;
            this.unavailableCount = unavailableCount;
            int preview = 0;
            for (Track track : tracks) {
                if (track.previewOnly) {
                    preview++;
                }
            }
            this.previewCount = preview;
        }

        long totalDurationMs() {
            long sum = 0;
            for (Track track : tracks) {
                sum += Math.max(0, track.durationMs);
            }
            return sum;
        }
    }

    /** set 链接（或可能是短链）才需要先尝试按播放列表解析。 */
    static boolean looksLikeSet(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("/sets/") || lower.contains("/playlists/")
                || lower.contains("on.soundcloud.com");
    }

    /**
     * 抓取并解析 set。
     *
     * @return 解析成功返回曲目清单；页面里没有 playlist 结构（普通单曲）返回 {@code null}
     */
    static Set fetch(String pageUrl) throws IOException {
        Page page = fetchPage(normalizeHost(pageUrl));
        // 短链可能 302 到 m.soundcloud.com（移动端页面没有 hydration），再抓一次桌面版
        if (page.finalUrl != null && page.finalUrl.contains("://m.soundcloud.com/")) {
            page = fetchPage(normalizeHost(page.finalUrl));
        }
        String json = extractHydration(page.html);
        if (json == null) {
            return null;
        }
        try {
            JSONArray root = new JSONArray(json);
            JSONObject playlist = null;
            String clientId = "";
            for (int i = 0; i < root.length(); i++) {
                JSONObject item = root.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String hydratable = item.optString("hydratable");
                if ("playlist".equals(hydratable)) {
                    playlist = item.optJSONObject("data");
                } else if ("apiClient".equals(hydratable)) {
                    JSONObject api = item.optJSONObject("data");
                    if (api != null) {
                        clientId = clean(api.optString("id"));
                    }
                }
            }
            if (playlist == null) {
                return null;
            }
            String title = clean(playlist.optString("title"));
            JSONObject user = playlist.optJSONObject("user");
            String artist = user == null ? "" : clean(user.optString("username"));
            String artwork = clean(playlist.optString("artwork_url"));

            JSONArray array = playlist.optJSONArray("tracks");
            if (array == null) {
                array = new JSONArray();
            }
            List<JSONObject> raw = new ArrayList<>();
            List<Long> missingIds = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                raw.add(item);
                if (item != null && trackUrl(item).isEmpty() && item.optLong("id", 0L) > 0L) {
                    missingIds.add(item.optLong("id", 0L));
                }
            }
            int declared = Math.max(playlist.optInt("track_count", 0), raw.size());

            // 一：页面内嵌的完整曲目
            List<Track> best = buildTracks(raw, new HashMap<Long, JSONObject>(), artist);
            Map<String, Long> durationByUrl = durationsOf(best);
            Map<String, Boolean> previewByUrl = previewsOf(best);

            // 二：批量补全只有 id 的占位曲目
            if (best.size() < declared && !missingIds.isEmpty() && !clientId.isEmpty()) {
                Map<Long, JSONObject> hydrated = hydrateTracks(clientId, missingIds);
                List<Track> filled = buildTracks(raw, hydrated, artist);
                if (filled.size() > best.size()) {
                    best = filled;
                    durationByUrl = durationsOf(best);
                    previewByUrl = previewsOf(best);
                }
            }

            // 三：移动端页面会渲染完整曲目列表（桌面页对未登录访问只内联前 5 首，
            // 且被省略的条目在 api-v2 里可能已失效，但移动端 DOM 仍保留真实链接）
            if (best.size() < declared) {
                String canonical = page.finalUrl == null ? normalizeHost(pageUrl) : page.finalUrl;
                List<Track> mobile = fetchMobileTracks(canonical, artist, durationByUrl, previewByUrl);
                if (mobile.size() > best.size()) {
                    best = mobile;
                }
            }

            if (best.isEmpty()) {
                return null;
            }
            int total = Math.max(declared, best.size());
            int unavailable = Math.max(0, total - best.size());
            return new Set(title.isEmpty() ? artist : title, artist, artwork, pageUrl,
                    best, total, unavailable);
        } catch (Exception e) {
            throw new IOException("播放列表解析失败", e);
        }
    }

    /** 把 hydration 里的曲目对象转换成有序列表；{@code hydrated} 用于补全占位条目。 */
    private static List<Track> buildTracks(List<JSONObject> raw, Map<Long, JSONObject> hydrated,
                                           String fallbackArtist) {
        List<Track> out = new ArrayList<>();
        for (JSONObject item : raw) {
            JSONObject full = item;
            if (item != null) {
                JSONObject resolved = hydrated.get(item.optLong("id", 0L));
                if (resolved != null) {
                    full = resolved;
                }
            }
            String url = full == null ? "" : trackUrl(full);
            if (url.isEmpty()) {
                continue;
            }
            String trackTitle = clean(full.optString("title"));
            if (trackTitle.isEmpty()) {
                trackTitle = url.substring(url.lastIndexOf('/') + 1);
            }
            JSONObject user = full.optJSONObject("user");
            String trackArtist = user == null ? fallbackArtist : clean(user.optString("username"));
            Track track = new Track(trackTitle, trackArtist, url, full.optLong("duration", 0));
            track.previewOnly = isPreviewOnly(full);
            out.add(track);
        }
        return out;
    }

    /**
     * 是否只能试听。
     *
     * <p>{@code policy=SNIP} 表示免费账号只能拿到 30 秒片段（Go+ 专享 / 地区限制）；
     * 若条目带有 media 信息，则再以「所有转码都标记 snipped」作为兜底判断。
     */
    private static boolean isPreviewOnly(JSONObject item) {
        if (item == null) {
            return false;
        }
        if ("SNIP".equalsIgnoreCase(clean(item.optString("policy")))) {
            return true;
        }
        JSONObject media = item.optJSONObject("media");
        JSONArray transcodings = media == null ? null : media.optJSONArray("transcodings");
        if (transcodings == null || transcodings.length() == 0) {
            return false;
        }
        for (int i = 0; i < transcodings.length(); i++) {
            JSONObject transcoding = transcodings.optJSONObject(i);
            if (transcoding != null && !transcoding.optBoolean("snipped", false)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Long> durationsOf(List<Track> tracks) {
        Map<String, Long> out = new HashMap<>();
        for (Track track : tracks) {
            out.put(track.url, track.durationMs);
        }
        return out;
    }

    private static Map<String, Boolean> previewsOf(List<Track> tracks) {
        Map<String, Boolean> out = new HashMap<>();
        for (Track track : tracks) {
            out.put(track.url, track.previewOnly);
        }
        return out;
    }

    /**
     * 从移动端页面 DOM 解析完整曲目列表。
     *
     * <p>移动端把每个曲目渲染成 {@code <a aria-label="标题" href="/user/slug?in=...">}，
     * 顺序与播放列表一致；桌面页的 hydration 只内联前 5 首，这里正好补齐。
     */
    private static List<Track> fetchMobileTracks(String canonicalUrl, String artist,
                                                 Map<String, Long> durations,
                                                 Map<String, Boolean> previews) {
        List<Track> out = new ArrayList<>();
        String mobileUrl = toMobileUrl(canonicalUrl);
        if (mobileUrl.isEmpty()) {
            return out;
        }
        try {
            String html = fetchPage(mobileUrl, UA_MOBILE).html;
            String expectedIn = setPath(canonicalUrl);
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            java.util.regex.Matcher anchor = ANCHOR.matcher(html);
            while (anchor.find()) {
                String tag = anchor.group();
                java.util.regex.Matcher hrefMatcher = HREF.matcher(tag);
                if (!hrefMatcher.find()) {
                    continue;
                }
                String href = hrefMatcher.group(1);
                int query = href.indexOf("?in=");
                if (query <= 0) {
                    continue;
                }
                // 只接受属于当前 set 的曲目，排除页面底部的「相关曲目 / 推荐歌单」
                if (!expectedIn.isEmpty()
                        && !expectedIn.equalsIgnoreCase(decodeIn(href.substring(query + 4)))) {
                    continue;
                }
                String path = href.substring(0, query);
                if (!path.startsWith("/") || path.contains("/sets")) {
                    continue;
                }
                String url = "https://soundcloud.com" + path;
                if (!seen.add(url)) {
                    continue;
                }
                String trackTitle = "";
                java.util.regex.Matcher label = ARIA_LABEL.matcher(tag);
                if (label.find()) {
                    trackTitle = unescape(label.group(1)).trim();
                }
                if (trackTitle.isEmpty()) {
                    trackTitle = path.substring(path.lastIndexOf('/') + 1);
                }
                Long duration = durations == null ? null : durations.get(url);
                Boolean preview = previews == null ? null : previews.get(url);
                Track track = new Track(trackTitle, artist, url,
                        duration == null ? 0L : duration);
                track.previewOnly = Boolean.TRUE.equals(preview);
                out.add(track);
            }
        } catch (Exception ignored) {
            // 移动端页面不可用时保留已有结果，不中断解析
        }
        return out;
    }

    private static String toMobileUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.replaceFirst("(?i)^https?://(www\\.)?soundcloud\\.com/",
                "https://m.soundcloud.com/");
    }

    /** 取 set URL 的路径（不含前导 /），用于匹配曲目链接的 {@code in=} 参数。 */
    private static String setPath(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path == null) {
                return "";
            }
            return path.startsWith("/") ? path.substring(1) : path;
        } catch (Exception e) {
            return "";
        }
    }

    private static String decodeIn(String raw) {
        if (raw == null) {
            return "";
        }
        int amp = raw.indexOf('&');
        String value = amp >= 0 ? raw.substring(0, amp) : raw;
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    /**
     * 用 api-v2 批量补全曲目信息。
     *
     * <p>页面 hydration 与 playlist 接口都只内联前若干首，剩余条目只有 id；
     * {@code /tracks?ids=a,b,c} 可以一次取回完整信息，既保证曲目数量也保证曲序。
     */
    private static Map<Long, JSONObject> hydrateTracks(String clientId, List<Long> ids)
            throws IOException {
        Map<Long, JSONObject> out = new HashMap<>();
        for (int from = 0; from < ids.size(); from += HYDRATE_BATCH) {
            int to = Math.min(ids.size(), from + HYDRATE_BATCH);
            StringBuilder joined = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(ids.get(i));
            }
            String url = "https://api-v2.soundcloud.com/tracks?ids=" + joined
                    + "&client_id=" + enc(clientId);
            try {
                JSONArray array = new JSONArray(fetchText(url));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.optJSONObject(i);
                    if (track != null) {
                        out.put(track.optLong("id", 0L), track);
                    }
                }
            } catch (Exception ignored) {
                // 单批补全失败时保留占位，后续按「不可用」处理，不中断整张专辑
            }
        }
        return out;
    }

    /**
     * 取曲目链接。
     *
     * <p>部分页面只给 {@code permalink} 而不给 {@code permalink_url}，
     * 这时用「用户 permalink + 曲目 permalink」拼出标准地址；
     * 连 permalink 都没有的条目是服务端占位，返回空串由调用方补全或计入不可用。
     */
    private static String trackUrl(JSONObject item) {
        String url = clean(item.optString("permalink_url"));
        if (!url.isEmpty()) {
            return url;
        }
        String permalink = clean(item.optString("permalink"));
        if (permalink.isEmpty()) {
            return "";
        }
        JSONObject user = item.optJSONObject("user");
        String userPermalink = user == null ? "" : clean(user.optString("permalink"));
        if (userPermalink.isEmpty()) {
            return "";
        }
        return "https://soundcloud.com/" + userPermalink + "/" + permalink;
    }

    /** 从页面 {@code __sc_hydration} 里取 {@code apiClient.id}（即 api-v2 的 client_id）。 */
    static String apiClientIdFromHtml(String html) {
        String json = extractHydration(html);
        if (json == null) {
            return "";
        }
        try {
            JSONArray root = new JSONArray(json);
            for (int i = 0; i < root.length(); i++) {
                JSONObject item = root.optJSONObject(i);
                if (item == null || !"apiClient".equals(item.optString("hydratable"))) {
                    continue;
                }
                JSONObject api = item.optJSONObject("data");
                if (api != null) {
                    return clean(api.optString("id"));
                }
            }
        } catch (Exception ignored) {
            // 页面结构变化时返回空串，由调用方走后备方案
        }
        return "";
    }

    /** 取出 {@code window.__sc_hydration = [...]} 里的数组文本。 */
    private static String extractHydration(String html) {
        int marker = html.indexOf("__sc_hydration");
        if (marker < 0) {
            return null;
        }
        int start = html.indexOf('[', marker);
        if (start < 0) {
            return null;
        }
        int end = html.indexOf("</script>", start);
        if (end < 0) {
            return null;
        }
        String raw = html.substring(start, end).trim();
        if (raw.endsWith(";")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        return raw.startsWith("[") ? raw : null;
    }

    /** 统一桌面版域名：移动端页面没有 hydration，无法解析曲目清单。 */
    private static String normalizeHost(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        return value.replaceFirst("(?i)^https?://m\\.soundcloud\\.com/",
                "https://soundcloud.com/");
    }

    private static final class Page {
        final String html;
        final String finalUrl;

        Page(String html, String finalUrl) {
            this.html = html;
            this.finalUrl = finalUrl;
        }
    }

    private static Page fetchPage(String urlStr) throws IOException {
        return fetchPage(urlStr, UA);
    }

    private static Page fetchPage(String urlStr, String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            String finalUrl = conn.getURL() == null ? urlStr : conn.getURL().toString();
            return new Page(readBounded(conn), finalUrl);
        } finally {
            conn.disconnect();
        }
    }

    private static String fetchText(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            return readBounded(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String readBounded(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                total += len;
                if (total > MAX_BYTES) {
                    throw new IOException("响应内容过大");
                }
                out.write(buf, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String text) throws IOException {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            throw new IOException("参数编码失败", e);
        }
    }

    /** JSON 里缺失字段时 optString 会返回 "null"，这里统一清洗。 */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equals(trimmed) ? "" : trimmed;
    }
}
