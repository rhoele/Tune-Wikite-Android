package com.auramusic.wiki.classic;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.Locale;

/**
 * lucida.to 客户端（对齐官方接口技术报告 + Jude-A/lucidadl 的调用方式）。
 *
 * <p>所有业务端点都必须经过 <b>统一入口</b> {@code /api/load?url=<内层路径>}；
 * 直接把内层路径当普通 URL 请求会失败。标准链路为：
 * <ol>
 *   <li>元数据：{@code /api/fetch/metadata} → 曲目信息与封面；</li>
 *   <li>建任务：POST {@code /api/fetch/stream/v2} → {@code handoff} + {@code server}；</li>
 *   <li>轮询：{@code https://<server>.lucida.to/api/fetch/request/<handoff>}；</li>
 *   <li>取文件：同一路径加 {@code /download}。</li>
 * </ol>
 *
 * <p>该服务经常返回「HTTP 200 空壳 JSON」，因此成功判定必须是
 * <b>合法 JSON 且包含 success 字段</b>，下载环节还必须校验 Content-Type 不是 HTML。
 * 建任务失败按 2s / 4s 重试三次。
 */
final class LucidaClient {

    private static final String BASE = "https://lucida.to";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    private static final long POLL_TIMEOUT_MS = 20 * 60 * 1000L;

    private LucidaClient() {
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
        String account = "";
        String service = "";
    }

    /** 与 lucida 前端的 downscale 取值一致；顺序即下拉菜单顺序。 */
    static final String[] FORMATS = {
            "m4a-aac-320", "m4a-aac-256", "m4a-aac-192", "mp3-320", "mp3-192",
            "original", "flac-16", "wav", "opus-96", "bitcrush"
    };

    static String formatLabel(String value) {
        switch (value) {
            case "original":
                return "original（AAC 256，非无损）";
            case "wav":
                return "wav（16 bit PCM）";
            case "flac-16":
                return "flac-16（无损压缩）";
            case "bitcrush":
                return "bitcrush（约 8 kbps，刻意降质）";
            case "opus-96":
                return "opus-96";
            case "m4a-aac-320":
                return "m4a-aac-320";
            case "m4a-aac-256":
                return "m4a-aac-256";
            case "m4a-aac-192":
                return "m4a-aac-192";
            case "mp3-320":
                return "mp3-320";
            case "mp3-192":
                return "mp3-192";
            default:
                return value;
        }
    }

    /** 取元数据；失败按 2s / 4s 重试三次。 */
    static Track metadata(String pageUrl) throws IOException {
        String inner = "/api/fetch/metadata?url=" + enc(pageUrl);
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject root = new JSONObject(request("GET", loadUrl(inner), null));
                if (!root.optBoolean("success", false)) {
                    throw new IOException("服务返回失败：" + root.optString("error", "空响应"));
                }
                Track track = new Track();
                track.title = root.optString("title", "");
                track.url = root.optString("url", pageUrl);
                track.durationMs = root.optLong("durationMs", 0);
                JSONArray artists = root.optJSONArray("artists");
                if (artists != null && artists.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < artists.length(); i++) {
                        JSONObject a = artists.optJSONObject(i);
                        if (a == null) {
                            continue;
                        }
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(a.optString("name", ""));
                    }
                    track.artist = sb.toString();
                }
                JSONArray covers = root.optJSONArray("coverArtwork");
                if (covers != null && covers.length() > 0) {
                    JSONObject c = covers.optJSONObject(0);
                    if (c != null) {
                        track.coverUrl = c.optString("url", "");
                    }
                }
                JSONObject stats = root.optJSONObject("stats");
                if (stats != null) {
                    track.account = stats.optString("account", "");
                    track.service = stats.optString("service", "");
                }
                return track;
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e
                        : new IOException("响应解析失败", e);
                sleep(2000L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("元数据请求失败") : last;
    }

    /**
     * 建任务并轮询到完成，最后把文件流式写入 {@code dir}。
     *
     * @return 已落盘的音频文件
     */
    static File download(String pageUrl, String downscale, String account, String title,
                         File dir, Progress progress) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        String handoff = null;
        String server = null;
        IOException lastError = null;
        for (int attempt = 0; attempt < 3 && handoff == null; attempt++) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("url", pageUrl);
                payload.put("downscale", downscale);
                payload.put("metadata", true);
                payload.put("compat", false);
                payload.put("private", true);
                payload.put("handoff", true);
                payload.put("upload", new JSONObject().put("enabled", false));
                JSONObject token = new JSONObject();
                token.put("primary", JSONObject.NULL);
                token.put("secondary", JSONObject.NULL);
                token.put("expiry", JSONObject.NULL);
                payload.put("token", token);
                JSONObject accountObj = new JSONObject();
                if (account != null && !account.isEmpty() && !"auto".equalsIgnoreCase(account)) {
                    accountObj.put("type", "account");
                    accountObj.put("id", account);
                } else {
                    accountObj.put("type", "country");
                    accountObj.put("id", "auto");
                }
                payload.put("account", accountObj);

                JSONObject root = new JSONObject(
                        request("POST", loadUrl("/api/fetch/stream/v2"), payload.toString()));
                String h = root.optString("handoff", "");
                if (!root.optBoolean("success", false) || h.isEmpty()) {
                    throw new IOException(root.optString("error", "建任务失败（空响应）"));
                }
                handoff = h;
                server = root.optString("server", root.optString("name", "katze"));
            } catch (Exception e) {
                lastError = e instanceof IOException ? (IOException) e
                        : new IOException("建任务失败", e);
                if (progress != null) {
                    progress.onStatus("建任务失败，重试中：" + brief(e.getMessage()));
                }
                sleep(2000L * (attempt + 1));
            }
        }
        if (handoff == null || server == null || server.isEmpty()) {
            throw lastError == null ? new IOException("建任务失败") : lastError;
        }

        String jobUrl = "https://" + server + ".lucida.to/api/fetch/request/" + handoff;
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        boolean completed = false;
        String lastMessage = "";
        while (System.currentTimeMillis() < deadline) {
            JSONObject status;
            try {
                status = new JSONObject(request("GET", jobUrl, null));
            } catch (Exception e) {
                if (progress != null) {
                    progress.onStatus("等待服务端处理…（" + brief(e.getMessage()) + "）");
                }
                sleep(2000);
                continue;
            }
            String state = status.optString("status", "");
            String message = status.optString("message", "");
            if (!message.isEmpty() && !message.equals(lastMessage) && progress != null) {
                lastMessage = message;
                progress.onStatus(message);
            }
            if ("completed".equals(state)) {
                completed = true;
                break;
            }
            if ("error".equals(state)) {
                throw new IOException("lucida：" + (message.isEmpty() ? "处理失败" : message));
            }
            sleep(1000);
        }
        if (!completed) {
            throw new IOException("处理超时，请稍后重试");
        }

        return fetchFile(jobUrl + "/download", dir, title, progress);
    }

    /** 下载文件流；按 Content-Type 映射扩展名，拒绝 HTML 错误页。 */
    private static File fetchFile(String urlStr, File dir, String title, Progress progress)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", BASE + "/");
        conn.setRequestProperty("Accept", "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream err = conn.getErrorStream();
                String text = err == null ? "" : new String(readAll(err, 4096), StandardCharsets.UTF_8);
                throw new IOException("下载失败 HTTP " + code + "：" + brief(text));
            }
            String type = conn.getContentType();
            if (type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                throw new IOException("服务返回了网页而不是文件");
            }
            String ext = extFor(type);
            int total = conn.getContentLength();
            File out = new File(dir, safeName(title) + "." + ext);
            // 先写 .part 再改名：下载中断时不会在最终文件名下留下「看起来正常」的残缺音频
            File part = new File(dir, out.getName() + ".part");
            try {
                try (InputStream in = conn.getInputStream();
                     OutputStream os = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    long read = 0;
                    int len;
                    int lastPercent = -1;
                    while ((len = in.read(buf)) != -1) {
                        os.write(buf, 0, len);
                        read += len;
                        if (progress != null && total > 0) {
                            int percent = (int) (read * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                progress.onPercent(percent);
                            }
                        }
                    }
                    os.flush();
                }
                if (part.length() <= 0) {
                    throw new IOException("下载结果为空");
                }
                if (!part.renameTo(out)) {
                    // 目标已存在（同名覆盖）或改名失败：删掉旧目标后重试一次
                    if (!out.delete() || !part.renameTo(out)) {
                        throw new IOException("写入文件失败");
                    }
                }
                return out;
            } catch (Exception e) {
                part.delete();
                throw e;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String extFor(String contentType) {
        if (contentType == null) {
            return "m4a";
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.contains("mpeg")) {
            return "mp3";
        }
        if (type.contains("flac")) {
            return "flac";
        }
        if (type.contains("wav")) {
            return "wav";
        }
        if (type.contains("ogg") || type.contains("opus")) {
            return "ogg";
        }
        // mp4 / aac 统一映射为 m4a，避免后缀与容器不一致
        return "m4a";
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }
        return cleaned.isEmpty() ? "audio" : cleaned;
    }

    private static String loadUrl(String innerPath) throws IOException {
        return BASE + "/api/load?url=" + enc(innerPath);
    }

    private static String enc(String text) throws IOException {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            throw new IOException("参数编码失败", e);
        }
    }

    private static String request(String method, String urlStr, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", BASE + "/");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            String text = new String(readAll(in, 2 * 1024 * 1024), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + "：" + brief(text));
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int len;
        while ((len = in.read(buf)) != -1) {
            total += len;
            if (total > max) {
                throw new IOException("响应体过大");
            }
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() > 160 ? one.substring(0, 160) : one;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
