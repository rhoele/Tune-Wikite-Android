package com.auramusic.wiki.classic;

import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SoundCloud 原封面抓取，复刻 rhoele/scimg 的核心策略：
 * <ol>
 *   <li>抓取曲目页，读取 {@code og:image}（两种属性顺序都兼容）与 {@code og:title}；</li>
 *   <li>去掉 CDN 主机与尺寸后缀，还原「母带基名 + 封面 ID」；</li>
 *   <li>按 <b>-original → -t1080x1080 → -t500x500</b> 顺序尝试，并在 i1~i4 四个 CDN
 *       主机间轮换，避免单节点抖动被误判为「没有原图」；</li>
 *   <li>校验响应确实是图片，并用 BitmapFactory 读取真实像素，确认不是错误页 / 占位图。</li>
 * </ol>
 * 注意 {@code -t1080x1080} 是服务端插值放大，优先 -original 才有真正的母带细节。
 */
final class SoundCloudCoverClient {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    private static final Pattern OG_IMAGE_PROP_FIRST = Pattern.compile(
            "property=\"og:image\"[^>]*content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_CONTENT_FIRST = Pattern.compile(
            "content=\"([^\"]+)\"[^>]*property=\"og:image\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "property=\"og:title\"[^>]*content=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_PREFIX = Pattern.compile(
            "^https?://i\\d\\.sndcdn\\.com/", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE_SUFFIX = Pattern.compile(
            "-(original|t\\d+x\\d+|crop|large|small|tiny|mini|badge)"
                    + "(,[^.\\n]*)?\\.(jpg|jpeg|png|webp)$", Pattern.CASE_INSENSITIVE);

    private static final String[] HOSTS = {"i1", "i2", "i3", "i4"};
    private static final String[] SIZES = {"original", "t1080x1080", "t500x500"};

    private SoundCloudCoverClient() {
    }

    static final class Result {
        final File file;
        final String title;
        final String variant;
        final int width;
        final int height;

        Result(File file, String title, String variant, int width, int height) {
            this.file = file;
            this.title = title;
            this.variant = variant;
            this.width = width;
            this.height = height;
        }

        boolean isOriginal() {
            return "original".equals(variant);
        }
    }

    /**
     * 抓取封面并写入 {@code dir}。
     *
     * @param pageUrl          曲目页（支持 soundcloud.com / 短链 / 分享文案里提取出的链接）
     * @param fallbackCoverUrl 网页被风控时的兜底封面（通常来自 lucida 元数据）
     * @param fallbackTitle    兜底标题（网页无法解析时用于命名）
     */
    static Result fetch(String pageUrl, String fallbackCoverUrl, File dir, String fallbackTitle)
            throws IOException {
        String url = normalizeUrl(pageUrl);
        if (url.isEmpty()) {
            throw new IOException("链接为空");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建封面目录");
        }

        String html = null;
        try {
            html = fetchText(url, 4 * 1024 * 1024);
        } catch (IOException ignored) {
            // 风控 / 202 空响应时直接使用兜底封面
        }

        String artwork = null;
        String title = null;
        if (html != null && !html.isEmpty()) {
            artwork = firstGroup(OG_IMAGE_PROP_FIRST, html);
            if (artwork == null) {
                artwork = firstGroup(OG_IMAGE_CONTENT_FIRST, html);
            }
            title = firstGroup(OG_TITLE, html);
        }
        if (artwork == null || artwork.isEmpty()) {
            artwork = fallbackCoverUrl;
        }
        if (artwork == null || artwork.isEmpty()) {
            throw new IOException("页面里没有找到封面地址（可能被风控）");
        }
        if (title == null || title.trim().isEmpty()) {
            title = fallbackTitle;
        }
        title = unescapeHtml(title == null ? "" : title).trim();

        String base = HOST_PREFIX.matcher(artwork).replaceFirst("");
        int q = base.indexOf('?');
        if (q >= 0) {
            base = base.substring(0, q);
        }
        Matcher suffix = SIZE_SUFFIX.matcher(base);
        if (suffix.find()) {
            base = base.substring(0, suffix.start());
        }
        if (base.isEmpty()) {
            throw new IOException("无法解析封面基名");
        }
        String coverId = coverIdOf(base);

        IOException last = null;
        for (String size : SIZES) {
            for (String host : HOSTS) {
                String candidate = "https://" + host + ".sndcdn.com/" + base + "-" + size + ".jpg";
                try {
                    byte[] data = fetchBytes(candidate, 20 * 1024 * 1024);
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    if (bounds.outWidth < 100 || bounds.outHeight < 100) {
                        last = new IOException("封面尺寸异常");
                        continue;
                    }
                    String ext = extOf(data);
                    File out = new File(dir, safeName(title) + " [" + coverId + "]." + ext);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                    }
                    return new Result(out, title, size, bounds.outWidth, bounds.outHeight);
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last == null ? new IOException("所有 CDN 节点均不可用") : last;
    }

    /** 从分享文案里提取 SoundCloud 链接。 */
    static String extractUrl(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("https?://(?:www\\.|m\\.)?soundcloud\\.com/[^\\s\"<>()，。）]+")
                .matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    private static String normalizeUrl(String raw) {
        String url = extractUrl(raw);
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    private static String coverIdOf(String base) {
        int dash = base.lastIndexOf('-');
        if (dash >= 0 && dash + 1 < base.length()) {
            String id = base.substring(dash + 1);
            if (id.length() >= 4 && id.length() <= 12) {
                return id;
            }
        }
        return String.valueOf(Math.abs(base.hashCode()) % 1000000);
    }

    private static String extOf(byte[] data) {
        if (data.length > 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "png";
        }
        if (data.length > 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return "jpg";
    }

    private static String safeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(0, 100);
        }
        return cleaned.isEmpty() ? "cover" : cleaned;
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String unescapeHtml(String text) {
        return text.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String fetchText(String urlStr, int maxBytes) throws IOException {
        HttpURLConnection conn = open(urlStr, UA);
        try (InputStream in = conn.getInputStream()) {
            return new String(readBounded(in, maxBytes), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] fetchBytes(String urlStr, int maxBytes) throws IOException {
        HttpURLConnection conn = open(urlStr, UA);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            String type = conn.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new IOException("不是图片：" + type);
            }
            try (InputStream in = conn.getInputStream()) {
                return readBounded(in, maxBytes);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String urlStr, String ua) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8");
        return conn;
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int len;
        while ((len = in.read(buf)) != -1) {
            total += len;
            if (total > maxBytes) {
                throw new IOException("响应体过大");
            }
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }
}
