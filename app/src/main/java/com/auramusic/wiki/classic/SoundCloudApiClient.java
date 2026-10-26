package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SoundCloud 官方接口客户端（直连 api-v2，不经过任何第三方下载站）。
 *
 * <p>调用链：
 * <ol>
 *   <li>{@code https://soundcloud.com/} 页面 hydration 里的 {@code apiClient.id} 即 client_id；</li>
 *   <li>{@code /resolve?url=…} 取曲目信息与 {@code media.transcodings}；</li>
 *   <li>转码地址（附带 {@code track_authorization}）返回真正的音源 URL；</li>
 *   <li>progressive 直接落盘；HLS 按 m3u8 顺序拼接分片。</li>
 * </ol>
 *
 * <p>此前链路依赖 lucida.to，该站现在对非浏览器客户端一律返回 Cloudflare 挑战（403），
 * 且其 SoundCloud 音源本身也不稳定，因此改为直连。
 */
final class SoundCloudApiClient {

    private static final String API = "https://api-v2.soundcloud.com";
    private static final String HOME = "https://soundcloud.com/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    /** client_id 缓存时长：SoundCloud 会轮换，过期后自动重新抓取 */
    private static final long CLIENT_TTL_MS = 6 * 60 * 60 * 1000L;

    private static final Pattern SCRIPT_SRC =
            Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[A-Za-z0-9_.-]+\\.js");
    private static final Pattern CLIENT_IN_JS =
            Pattern.compile("client_id\\s*[:=]\\s*[\"']([A-Za-z0-9]{32})[\"']");

    private SoundCloudApiClient() {
    }

    interface Progress {
        void onStatus(String text);

        void onPercent(int percent);
    }

    static final class Track {
        String title = "";
        String artist = "";
        long durationMs;
        String url = "";
        String coverUrl = "";
        String genre = "";
        String releaseDate = "";
        long id;
        /** 上传者开启了「允许下载」：可取原始文件，音质优先于任何转码 */
        boolean downloadable;
        /** SoundCloud 只提供 30 秒试听（Go+ 曲目） */
        boolean previewOnly;
    }

    /** 下拉选项：直连只能选「优先哪种容器」，不能像 lucida 那样服务端转码。 */
    static final String[] FORMATS = {"auto", "aac", "mp3", "opus"};

    static String formatLabel(String value) {
        switch (value) {
            case "aac":
                return "AAC / m4a（优先）";
            case "mp3":
                return "MP3（优先）";
            case "opus":
                return "Opus / ogg（优先）";
            default:
                return "自动（最佳可用音源）";
        }
    }

    private static volatile String clientId = "";
    private static volatile long clientIdAt;

    // ------------------------------------------------------------------
    // client_id
    // ------------------------------------------------------------------

    private static String clientId() throws IOException {
        String cached = clientId;
        if (!cached.isEmpty() && System.currentTimeMillis() - clientIdAt < CLIENT_TTL_MS) {
            return cached;
        }
        return refreshClientId();
    }

    private static synchronized String refreshClientId() throws IOException {
        // 这里必须真正重新抓取：调用方已经确认缓存的 id 被拒（401/403）或已过期
        String html = fetchText(HOME, "text/html,application/xhtml+xml", MAX_TEXT_BYTES);
        String id = SoundCloudSetClient.apiClientIdFromHtml(html);
        if (id.isEmpty()) {
            id = scrapeClientId(html);
        }
        if (id.isEmpty()) {
            throw new IOException("无法获取 SoundCloud client_id，请稍后重试");
        }
        clientId = id;
        clientIdAt = System.currentTimeMillis();
        return id;
    }

    /** 兜底：从页面引用的 JS 包里找硬编码的 client_id。 */
    private static String scrapeClientId(String html) {
        Matcher matcher = SCRIPT_SRC.matcher(html);
        Set<String> scripts = new LinkedHashSet<>();
        while (matcher.find() && scripts.size() < 12) {
            scripts.add(matcher.group());
        }
        for (String script : scripts) {
            try {
                String js = fetchText(script, "*/*", 4 * 1024 * 1024);
                Matcher id = CLIENT_IN_JS.matcher(js);
                if (id.find()) {
                    return id.group(1);
                }
            } catch (Exception ignored) {
                // 单个脚本失败不影响其它候选
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 元数据
    // ------------------------------------------------------------------

    static Track resolve(String pageUrl) throws IOException {
        final String url = normalize(pageUrl);
        return toTrack(trackJson(url), url);
    }

    private static JSONObject trackJson(String pageUrl) throws IOException {
        JSONObject json = resolveJson(pageUrl);
        String kind = clean(json.optString("kind"));
        if (!kind.isEmpty() && !"track".equals(kind)) {
            throw new IOException("该链接不是单曲（" + kind + "），请使用 /sets/ 播放列表链接");
        }
        return json;
    }

    private static JSONObject resolveJson(String pageUrl) throws IOException {
        return apiGet("/resolve?url=" + enc(pageUrl));
    }

    private static Track toTrack(JSONObject json, String fallbackUrl) {
        Track track = new Track();
        track.title = clean(json.optString("title"));
        JSONObject user = json.optJSONObject("user");
        track.artist = user == null ? "" : clean(user.optString("username"));
        track.durationMs = json.optLong("duration", 0L);
        track.id = json.optLong("id", 0L);
        track.downloadable = json.optBoolean("downloadable", false);
        track.url = clean(json.optString("permalink_url"));
        if (track.url.isEmpty()) {
            track.url = fallbackUrl;
        }
        track.coverUrl = artwork(json.optString("artwork_url"));
        track.genre = clean(json.optString("genre"));
        track.releaseDate = clean(json.optString("release_date"));
        track.previewOnly = "SNIP".equalsIgnoreCase(json.optString("policy"));
        JSONObject media = json.optJSONObject("media");
        JSONArray transcodings = media == null ? null : media.optJSONArray("transcodings");
        if (transcodings != null) {
            for (int i = 0; i < transcodings.length(); i++) {
                JSONObject item = transcodings.optJSONObject(i);
                if (item != null && item.optBoolean("snipped", false)) {
                    track.previewOnly = true;
                }
            }
        }
        return track;
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /**
     * 解析并下载一首曲目。
     *
     * @param prefer {@link #FORMATS} 中的容器偏好
     * @return 已落盘的音频文件
     */
    static File download(String pageUrl, String prefer, File dir, String title, Progress progress)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        final String url = normalize(pageUrl);
        JSONObject json = trackJson(url);
        Track track = toTrack(json, url);
        if (track.previewOnly) {
            throw new IOException("该曲目在 SoundCloud 仅提供试听片段（需 Go+），无法获取完整音频");
        }
        JSONObject transcoding = pickTranscoding(json, prefer);
        if (transcoding == null) {
            throw new IOException("没有可用的音源（可能受版权或地区限制）");
        }
        if (progress != null) {
            progress.onStatus("正在获取音源地址…");
        }
        JSONObject format = transcoding.optJSONObject("format");
        String protocol = format == null ? "" : format.optString("protocol", "");
        String mime = format == null ? "" : format.optString("mime_type", "");
        String mediaUrl = mediaUrl(json, transcoding);
        String baseName = safeName(title);
        if ("progressive".equals(protocol)) {
            return downloadStream(mediaUrl, dir, baseName, mime, progress);
        }
        return downloadHls(mediaUrl, dir, baseName, progress);
    }

    /** 转码地址需要 client_id，Go+ 曲目还需要 media.track_authorization。 */
    private static String mediaUrl(JSONObject track, JSONObject transcoding) throws IOException {
        String base = transcoding.optString("url", "");
        if (base.isEmpty()) {
            throw new IOException("音源地址为空");
        }
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String id = attempt == 0 ? clientId() : refreshClientId();
            StringBuilder url = new StringBuilder(base);
            url.append(base.contains("?") ? '&' : '?').append("client_id=").append(enc(id));
            JSONObject media = track.optJSONObject("media");
            String auth = media == null ? "" : media.optString("track_authorization", "");
            if (!auth.isEmpty()) {
                url.append("&track_authorization=").append(enc(auth));
            }
            try {
                JSONObject root = new JSONObject(fetchText(url.toString(), "application/json",
                        MAX_TEXT_BYTES));
                String direct = clean(root.optString("url"));
                if (!direct.isEmpty()) {
                    return direct;
                }
                throw new IOException("音源地址为空");
            } catch (IOException e) {
                last = e;
                if (!isAuthError(e)) {
                    throw e;
                }
            } catch (Exception e) {
                throw new IOException("音源响应解析失败", e);
            }
        }
        throw last == null ? new IOException("音源地址失效") : last;
    }

    /**
     * 选择转码：先按容器偏好，再按 quality，最后优先 progressive（单文件最稳）。
     */
    private static JSONObject pickTranscoding(JSONObject track, String prefer) {
        JSONObject media = track.optJSONObject("media");
        JSONArray array = media == null ? null : media.optJSONArray("transcodings");
        if (array == null) {
            return null;
        }
        JSONObject best = null;
        int bestScore = -1;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject format = item.optJSONObject("format");
            String protocol = format == null ? "" : format.optString("protocol", "");
            boolean progressive = "progressive".equals(protocol);
            if (!progressive && !"hls".equals(protocol)) {
                continue;
            }
            String mime = format == null ? "" : format.optString("mime_type", "").toLowerCase(Locale.ROOT);
            // 3=Opus / 2=MP3 / 1=AAC(mp4) / 0=codec 未知（如 abr 的 mpegurl 主播放列表）
            int type = mime.contains("opus") ? 3
                    : (mime.contains("mpegurl") ? 0 : (mime.contains("mpeg") ? 2 : 1));
            int typeScore;
            if ("opus".equals(prefer)) {
                typeScore = type == 3 ? 3 : (type == 1 ? 2 : 1);
            } else if ("mp3".equals(prefer)) {
                typeScore = type == 2 ? 3 : (type == 1 ? 2 : 1);
            } else {
                typeScore = type == 1 ? 3 : (type == 2 ? 2 : 1);
            }
            int score = typeScore * 100 + qualityScore(item.optString("quality")) * 10
                    + (progressive ? 40 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static int qualityScore(String quality) {
        if ("sq".equalsIgnoreCase(quality)) {
            return 3;
        }
        if ("hq".equalsIgnoreCase(quality)) {
            return 2;
        }
        return 1;
    }

    /** progressive：单文件直接流式写入，按 Content-Length 报进度。 */
    private static File downloadStream(String mediaUrl, File dir, String baseName, String mime,
                                       Progress progress) throws IOException {
        HttpURLConnection conn = open(mediaUrl, "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("音源下载失败 HTTP " + code);
            }
            String contentType = conn.getContentType();
            String ext = extFor(contentType == null || contentType.isEmpty() ? mime : contentType);
            File out = uniqueFile(dir, baseName, ext);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                copy(in, os, total, progress);
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * HLS：主播放列表挑最高带宽，再按顺序拼接分片。
     *
     * <p>带 {@code EXT-X-MAP} 的是 fMP4，拼出来就是标准 m4a；否则是 MPEG-TS，
     * 拼成 .ts（后续 32-bit WAV 转换仍可被 MediaExtractor 读取）。
     */
    private static File downloadHls(String m3u8Url, File dir, String baseName, Progress progress)
            throws IOException {
        String playlist = fetchText(m3u8Url, "application/vnd.apple.mpegurl,application/x-mpegURL,*/*",
                MAX_TEXT_BYTES);
        String mediaPlaylist = playlist;
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            String variant = pickVariant(playlist);
            if (variant.isEmpty()) {
                throw new IOException("HLS 播放列表缺少可用的清晰度");
            }
            String variantUrl = resolveRelative(m3u8Url, variant);
            mediaPlaylist = fetchText(variantUrl,
                    "application/vnd.apple.mpegurl,application/x-mpegURL,*/*", MAX_TEXT_BYTES);
            m3u8Url = variantUrl;
        }
        String initUrl = "";
        List<String> segments = new ArrayList<>();
        for (String raw : mediaPlaylist.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MAP:")) {
                int from = line.indexOf("URI=\"");
                if (from >= 0) {
                    int to = line.indexOf('"', from + 5);
                    if (to > from) {
                        initUrl = resolveRelative(m3u8Url, line.substring(from + 5, to));
                    }
                }
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }
            segments.add(resolveRelative(m3u8Url, line));
        }
        if (segments.isEmpty()) {
            throw new IOException("HLS 播放列表没有分片");
        }
        File out = uniqueFile(dir, baseName, initUrl.isEmpty() ? ".ts" : ".m4a");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            if (!initUrl.isEmpty()) {
                copyUrl(initUrl, os);
            }
            for (int i = 0; i < segments.size(); i++) {
                copyUrl(segments.get(i), os);
                if (progress != null) {
                    progress.onPercent((i + 1) * 100 / segments.size());
                }
            }
        }
        return out;
    }

    private static String pickVariant(String playlist) {
        String best = "";
        int bestBandwidth = -1;
        String[] lines = playlist.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }
            int bandwidth = 0;
            int from = line.indexOf("BANDWIDTH=");
            if (from >= 0) {
                int start = from + "BANDWIDTH=".length();
                int end = start;
                while (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++;
                }
                try {
                    bandwidth = Integer.parseInt(line.substring(start, end));
                } catch (Exception ignored) {
                    bandwidth = 0;
                }
            }
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) {
                    continue;
                }
                if (bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth;
                    best = next;
                }
                break;
            }
        }
        return best;
    }

    private static void copyUrl(String url, OutputStream os) throws IOException {
        HttpURLConnection conn = open(url, "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("分片下载失败 HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                copy(in, os, -1L, null);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream os, long total, Progress progress)
            throws IOException {
        byte[] buf = new byte[32768];
        long read = 0;
        int len;
        int lastPercent = -1;
        while ((len = in.read(buf)) != -1) {
            os.write(buf, 0, len);
            read += len;
            if (progress != null && total > 0) {
                int percent = (int) Math.min(100, read * 100 / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onPercent(percent);
                }
            }
        }
        os.flush();
    }

    // ------------------------------------------------------------------
    // 基础请求
    // ------------------------------------------------------------------

    private static JSONObject apiGet(String pathAndQuery) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String id = attempt == 0 ? clientId() : refreshClientId();
            String sep = pathAndQuery.contains("?") ? "&" : "?";
            try {
                return new JSONObject(fetchText(
                        API + pathAndQuery + sep + "client_id=" + enc(id), "application/json",
                        MAX_TEXT_BYTES));
            } catch (IOException e) {
                last = e;
                if (!isAuthError(e)) {
                    throw e;
                }
            } catch (Exception e) {
                throw new IOException("SoundCloud 响应解析失败", e);
            }
        }
        throw last == null ? new IOException("SoundCloud 接口请求失败") : last;
    }

    private static HttpURLConnection open(String urlStr, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", HOME);
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        return conn;
    }

    private static String fetchText(String urlStr, String accept, int maxBytes) throws IOException {
        HttpURLConnection conn = open(urlStr, accept);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int total = 0;
                int len;
                while ((len = in.read(buf)) != -1) {
                    total += len;
                    if (total > maxBytes) {
                        throw new IOException("响应内容过大");
                    }
                    out.write(buf, 0, len);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isAuthError(IOException e) {
        String message = e.getMessage();
        return message != null && (message.contains("HTTP 401") || message.contains("HTTP 403"));
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.trim().replaceFirst("(?i)^https?://m\\.soundcloud\\.com/",
                "https://soundcloud.com/");
    }

    private static String artwork(String url) {
        String value = clean(url);
        if (value.isEmpty()) {
            return "";
        }
        value = value.replace("-large.", "-t500x500.");
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        return value;
    }

    private static String extFor(String mime) {
        String value = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (value.contains("mpeg")) {
            return ".mp3";
        }
        if (value.contains("opus") || value.contains("ogg")) {
            return ".ogg";
        }
        if (value.contains("flac")) {
            return ".flac";
        }
        return ".m4a";
    }

    private static File uniqueFile(File dir, String baseName, String ext) {
        File file = new File(dir, baseName + ext);
        for (int i = 2; file.exists() && i < 100; i++) {
            file = new File(dir, baseName + " (" + i + ")" + ext);
        }
        return file;
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "" : name
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .trim();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100).trim();
        }
        return cleaned.isEmpty() ? "soundcloud-audio" : cleaned;
    }

    private static String resolveRelative(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static String enc(String text) throws IOException {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            throw new IOException("参数编码失败", e);
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return "null".equals(trimmed) ? "" : trimmed;
    }
}
