package com.auramusic.wiki.classic;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 网易云 NOS 上传（专辑封面 / 歌曲音频）。
 *
 * <p>与原 APK 完全一致的链路：
 * <ol>
 *   <li>图片凭证：{@code POST /api/nos/token/alloc}（bucket=yyimgs, nos_product=0）；</li>
 *   <li>音频凭证：{@code POST /api/nos/token/whalealloc}（bucket=ymusic, bizKey=3a92052e，
 *       带文件大小与 MD5）；</li>
 *   <li>文件分块上传到 {@code nosup-hz1.127.net}，参数 offset / complete / version / objectkey，
 *       头 {@code x-nos-token} 与 {@code Content-MD5}；失败时回退网页代理
 *       {@code music.163.com/web/proxy/nosupload}。</li>
 * </ol>
 * 返回的 JSON 直接作为 albumVo 的 {@code albumRawNosKey} / {@code audio} 字段来源。
 */
final class NosUploader {

    private static final String UA_JSON = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String UA_UPLOAD = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/st/wiki/";
    private static final int CHUNK_SIZE = 4 * 1024 * 1024;

    private NosUploader() {
    }

    interface Progress {
        void onPercent(int percent);
    }

    static final class NosFile {
        final String fileName;
        final long fileSize;
        final String mime;
        final String bucket;
        final String objectKey;
        final String docId;
        final String resourceId;
        final String md5;

        NosFile(String fileName, long fileSize, String mime, String bucket, String objectKey,
                String docId, String resourceId, String md5) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.mime = mime;
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.docId = docId;
            this.resourceId = resourceId;
            this.md5 = md5;
        }

        String nosKey() {
            return bucket + "/" + objectKey;
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("code", 200)
                    .put("fileName", fileName)
                    .put("fileSize", fileSize)
                    .put("type", mime)
                    .put("bucket", bucket)
                    .put("docId", docId)
                    .put("objectKey", objectKey)
                    .put("resourceId", resourceId)
                    .put("md5", md5)
                    .put("jdUrl", bucket + ".nos-jd.163yun.com/" + objectKey)
                    .put("hzUrl", "nos.netease.com/" + bucket + "/" + objectKey)
                    .put("nosKey", nosKey());
        }
    }

    /** 图片上传（封面原图 / 1:1 裁剪）。 */
    static NosFile uploadImage(Context context, File file, String mime, Progress progress)
            throws IOException {
        String cookie = requireCookie(context);
        String ext = file.getName().contains(".")
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1) : "jpg";
        String md5 = Md5.hex(file);
        JSONObject token = allocToken(cookie, file.getName(), ext, "image", "yyimgs",
                0, null);
        return uploadWithToken(file, token, normalizeMime(mime, file.getName(), true), md5,
                progress);
    }

    /** 音频上传。 */
    static NosFile uploadAudio(Context context, File file, String mime, Progress progress)
            throws IOException {
        String cookie = requireCookie(context);
        String ext = file.getName().contains(".")
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1) : "m4a";
        String md5 = Md5.hex(file);
        JSONObject token = allocToken(cookie, file.getName(), ext, "audio", "ymusic",
                file.length(), md5);
        return uploadWithToken(file, token, normalizeMime(mime, file.getName(), false), md5,
                progress);
    }

    /** 分块请求的 Content-Type 必须是真实类型，NOS 会按它校验对象类型。 */
    private static String normalizeMime(String mime, String fileName, boolean image) {
        String prefix = image ? "image/" : "audio/";
        String value = mime == null ? "" : mime.trim();
        if (!value.startsWith(prefix)) {
            value = guessMime(fileName);
        }
        if (!value.startsWith(prefix)) {
            value = image ? "image/jpeg" : "audio/mp4";
        }
        if ("audio/mp3".equals(value)) {
            value = "audio/mpeg";
        } else if ("audio/x-m4a".equals(value)) {
            value = "audio/mp4";
        }
        return value;
    }

    private static String requireCookie(Context context) throws IOException {
        String cookie = WikiPrefs.cookie(context);
        if (cookie == null || cookie.isEmpty()) {
            throw new IOException("未登录，无法上传文件");
        }
        return cookie;
    }

    private static JSONObject allocToken(String cookie, String filename, String ext, String type,
                                         String bucket, long fileSize, String md5)
            throws IOException {
        StringBuilder form = new StringBuilder();
        form.append("filename=").append(enc(filename));
        if ("image".equals(type)) {
            form.append("&ext=").append(enc(ext));
            form.append("&type=image");
            form.append("&bucket=").append(bucket);
            form.append("&local=false");
            form.append("&nos_product=0");
        } else {
            form.append("&fileSize=").append(fileSize);
            form.append("&type=audio");
            form.append("&bucket=").append(bucket);
            form.append("&bizKey=3a92052e");
            form.append("&md5=").append(enc(md5));
        }
        String url = "https://music.163.com/api/nos/token/"
                + ("image".equals(type) ? "alloc" : "whalealloc");
        String body;
        try {
            // 与原 APK 一致：Cookie 前置 os=pc; appver=2.10.13，缺失会被服务端按来源不明处理
            body = Http.postForm(url, form.toString(), UA_JSON, REFERER,
                    "https://music.163.com", NetEaseWeApi.cookieHeader(cookie), null);
        } catch (WikiException e) {
            throw new IOException("获取上传凭证失败：" + e.getMessage(), e);
        }
        try {
            JSONObject root = new JSONObject(body);
            // 与原 APK 一致：只把 data/result 当作凭证对象；code=301 表示登录失效
            JSONObject payload = unwrap(root);
            int code = root.optInt("code", 200);
            if (code == 301) {
                throw new IOException("登录已失效，请重新登录");
            }
            String bucketValue = firstNonEmpty(payload.optString("bucket"),
                    root.optString("bucket"));
            if (bucketValue.isEmpty()) {
                // 原 APK 行为：响应不带 bucket 时按类型回退默认桶
                bucketValue = bucket;
            }
            String objectKey = firstNonEmpty(payload.optString("objectKey"),
                    payload.optString("objectkey"), root.optString("objectKey"),
                    root.optString("objectkey"));
            String token = firstNonEmpty(payload.optString("token"),
                    payload.optString("nosToken"), root.optString("token"),
                    root.optString("nosToken"));
            if (objectKey.isEmpty() || token.isEmpty()) {
                String reason = firstNonEmpty(payload.optString("message"),
                        payload.optString("msg"), root.optString("message"),
                        root.optString("msg"));
                throw new IOException("上传凭证不完整"
                        + (reason.isEmpty() ? "" : "：" + reason)
                        + "（响应：" + brief(body) + "）");
            }
            // 统一返回带顶层字段的对象，供 uploadWithToken 读取
            JSONObject normalized = new JSONObject();
            normalized.put("bucket", bucketValue);
            normalized.put("objectKey", objectKey);
            normalized.put("token", token);
            normalized.put("docId", firstNonEmpty(payload.optString("docId"),
                    root.optString("docId")));
            normalized.put("resourceId", firstNonEmpty(payload.optString("resourceId"),
                    root.optString("resourceId")));
            return normalized;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("上传凭证不是合法 JSON（响应：" + brief(body) + "）", e);
        }
    }

    /** 兼容平铺 / data / result / data 字符串四种返回形态。 */
    private static JSONObject unwrap(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            return data;
        }
        Object raw = root.opt("data");
        if (raw instanceof String) {
            try {
                return new JSONObject((String) raw);
            } catch (Exception ignored) {
                // 继续尝试 result
            }
        }
        JSONObject result = root.optJSONObject("result");
        return result == null ? root : result;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        String one = body.replace('\n', ' ').trim();
        return one.length() > 220 ? one.substring(0, 220) : one;
    }

    private static NosFile uploadWithToken(File file, JSONObject token, String mime, String md5,
                                           Progress progress) throws IOException {
        String bucket = token.optString("bucket", "");
        String objectKey = token.optString("objectKey", "");
        String nosToken = token.optString("token", "");
        if (bucket.isEmpty() || objectKey.isEmpty() || nosToken.isEmpty()) {
            throw new IOException("上传凭证不完整");
        }
        String objectParam = enc(bucket + "/" + objectKey);
        String contentType = mime == null || mime.isEmpty()
                ? guessMime(file.getName()) : mime;
        long length = file.length();
        if (length <= 0) {
            throw new IOException("文件为空");
        }
        long offset = 0;
        String context = null;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            while (offset < length) {
                int want = (int) Math.min(CHUNK_SIZE, length - offset);
                byte[] chunk = new byte[want];
                in.seek(offset);
                in.readFully(chunk);
                boolean complete = offset + want >= length;
                String body = postChunk(chunk, offset, complete, bucket, objectKey, objectParam,
                        nosToken, context, Md5.base64(chunk), contentType);
                long next = offset + want;
                // 与原 APK 一致：优先采用服务端返回的 context / offset
                if (body != null && !body.isEmpty()) {
                    try {
                        JSONObject json = new JSONObject(body);
                        if (json.has("context")) {
                            context = json.optString("context", context);
                        }
                        if (json.has("offset")) {
                            next = json.optLong("offset", next);
                        }
                    } catch (Exception ignored) {
                        // 响应不是 JSON 时按本地偏移继续
                    }
                }
                offset = next > offset ? next : offset + want;
                if (progress != null) {
                    progress.onPercent((int) Math.min(100, offset * 100 / length));
                }
            }
        }
        return new NosFile(file.getName(), length, contentType, bucket, objectKey,
                token.optString("docId", ""), token.optString("resourceId", ""), md5);
    }

    private static String postChunk(byte[] data, long offset, boolean complete, String bucket,
                                    String objectKey, String objectParam, String nosToken,
                                    String context, String contentMd5, String contentType)
            throws IOException {
        IOException first = null;
        String query = "?offset=" + offset + "&complete=" + complete
                + "&version=1.0&objectkey=" + objectParam
                + (context == null || context.isEmpty() ? "" : "&context=" + enc(context));
        String[] urls = {
                "https://nosup-hz1.127.net/" + bucket + "/" + objectKey + query,
                "https://music.163.com/web/proxy/nosupload" + query
        };
        for (String url : urls) {
            try {
                return write(url, data, nosToken, contentMd5, contentType);
            } catch (IOException e) {
                first = e;
            }
        }
        throw first == null ? new IOException("上传失败") : first;
    }

    /** 上传单个分块；返回响应体，HTTP 非 2xx 时抛异常。 */
    private static String write(String urlStr, byte[] data, String nosToken, String contentMd5,
                                String contentType) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(90000);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(data.length);
        conn.setRequestProperty("User-Agent", UA_UPLOAD);
        conn.setRequestProperty("x-nos-token", nosToken);
        conn.setRequestProperty("Content-MD5", contentMd5);
        // 必须使用真实 MIME：NOS 会按 Content-Type 校验对象类型，octet-stream 会被拒绝
        conn.setRequestProperty("Content-Type", contentType);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream err = conn.getErrorStream();
                String detail = err == null ? ""
                        : new String(readAll(err, 4096), StandardCharsets.UTF_8);
                throw new IOException("上传失败（HTTP " + code + "）"
                        + (detail.isEmpty() ? "" : "：" + brief(detail)));
            }
            InputStream in = conn.getInputStream();
            return in == null ? "" : new String(readAll(in, 16384), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static String guessMime(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".ape")) {
            return "audio/ape";
        }
        return "audio/mp4";
    }

    private static String enc(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    private static String urlDecode(String text) {
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int len;
        while ((len = in.read(buf)) != -1) {
            total += len;
            if (total > max) {
                break;
            }
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }
}
