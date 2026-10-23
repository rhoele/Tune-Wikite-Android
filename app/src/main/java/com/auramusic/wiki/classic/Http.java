package com.auramusic.wiki.classic;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 轻量 HTTP 工具：统一超时、响应体上限、异常分级与可取消能力。
 * 避免各业务客户端重复实现，也避免把异常静默吞掉。
 */
final class Http {

    /** 默认响应体上限：2 MB。超出即中断，防止大响应撑爆内存（对应基准 readBounded）。 */
    static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

    /** 手动跟随跳转的最大跳数，防止畸形 Location 造成死循环 */
    private static final int MAX_REDIRECT_HOPS = 3;

    private static final int DEFAULT_CONNECT_TIMEOUT = 8000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    private Http() {
    }

    /** 可取消句柄：持有在途连接，调用 cancel() 立即断开。 */
    static final class CancelToken {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                    // 断开失败无需处理，连接会随 GC 释放
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 带响应头的返回体（扫码登录需要读取 Set-Cookie 完成会话交接）。 */
    static final class Response {
        final String body;
        /**
         * 原始 Set-Cookie 头，按出现顺序排列（可能为空，不会为 null）。
         * 关闭自动跳转后，跳转链路上每一跳下发的 Cookie 都会累积在这里。
         */
        final List<String> setCookies;
        /** 最终 HTTP 状态码 */
        final int statusCode;

        Response(String body, List<String> setCookies, int statusCode) {
            this.body = body;
            this.setCookies = setCookies;
            this.statusCode = statusCode;
        }
    }

    static String get(String urlStr, String userAgent, String referer, String cookie, CancelToken token)
            throws WikiException {
        return get(urlStr, userAgent, referer, cookie, token, DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES);
    }

    static String get(String urlStr, String userAgent, String referer, String cookie, CancelToken token,
                      int connectTimeout, int readTimeout, int maxBytes) throws WikiException {
        return getResponse(urlStr, userAgent, referer, cookie, token, connectTimeout, readTimeout, maxBytes).body;
    }

    static Response getResponse(String urlStr, String userAgent, String referer, String cookie,
                                CancelToken token) throws WikiException {
        return getResponse(urlStr, userAgent, referer, cookie, token, DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES);
    }

    static Response getResponse(String urlStr, String userAgent, String referer, String cookie,
                                CancelToken token, int connectTimeout, int readTimeout, int maxBytes)
            throws WikiException {
        return request("GET", urlStr, null, null, userAgent, referer, null, cookie, token,
                connectTimeout, readTimeout, maxBytes, null, true);
    }

    /**
     * 不自动跟随 3xx 跳转的 GET。
     *
     * <p>网易云登录成功后是<b>由跳转响应的 Set-Cookie 下发 {@code MUSIC_U}</b> 的：
     * 一旦交给连接层自动跟随，这份凭据就会随中间响应一起被丢弃，导致「已确认登录却拿不到会话」。
     * 这里关闭自动跳转，由 {@link #request} 手动跟一跳并把沿途 Cookie 累积到
     * {@link Response#setCookies} 中。
     */
    static Response getResponseNoRedirect(String urlStr, String userAgent, String referer,
                                          String cookie, CancelToken token) throws WikiException {
        return request("GET", urlStr, null, null, userAgent, referer, null, cookie, token,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES, null, false);
    }

    /**
     * 表单 POST（{@code application/x-www-form-urlencoded}）。
     * WEAPI 端点只接受表单 POST，明文 GET 会直接被服务端判为未登录。
     *
     * @param formBody 已 URL 编码的表单体，如 {@code params=xxx&encSecKey=yyy}
     */
    static String postForm(String urlStr, String formBody, String userAgent, String referer,
                           String origin, String cookie, CancelToken token) throws WikiException {
        return postFormResponse(urlStr, formBody, userAgent, referer, origin, cookie, token).body;
    }

    /** 表单 POST，保留响应头（扫码登录需要读取 Set-Cookie）。 */
    static Response postFormResponse(String urlStr, String formBody, String userAgent,
                                     String referer, String origin, String cookie,
                                     CancelToken token) throws WikiException {
        return request("POST", urlStr, formBody,
                "application/x-www-form-urlencoded; charset=UTF-8",
                userAgent, referer, origin, cookie, token,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES, null, true);
    }

    /** 表单 POST（不自动跟随跳转），用于扫码登录这类依赖跳转下发凭据的端点。 */
    static Response postFormResponseNoRedirect(String urlStr, String formBody, String userAgent,
                                               String referer, String origin, String cookie,
                                               CancelToken token) throws WikiException {
        return request("POST", urlStr, formBody,
                "application/x-www-form-urlencoded; charset=UTF-8",
                userAgent, referer, origin, cookie, token,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES, null, false);
    }

    /**
     * JSON POST（自有服务使用，如 FCM 令牌上报）。
     *
     * @param jsonBody 已序列化的 JSON 文本
     * @param bearer   可选 Bearer Token，null 表示不带鉴权头
     */
    static String postJson(String urlStr, String jsonBody, String bearer,
                           CancelToken token) throws WikiException {
        return request("POST", urlStr, jsonBody,
                "application/json; charset=UTF-8",
                null, null, null, null, token,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES,
                bearer).body;
    }

    private static Response request(String method, String urlStr, String body, String contentType,
                                    String userAgent, String referer, String origin, String cookie,
                                    CancelToken token, int connectTimeout, int readTimeout,
                                    int maxBytes, String bearer) throws WikiException {
        return request(method, urlStr, body, contentType, userAgent, referer, origin, cookie,
                token, connectTimeout, readTimeout, maxBytes, bearer, true, 0);
    }

    private static Response request(String method, String urlStr, String body, String contentType,
                                    String userAgent, String referer, String origin, String cookie,
                                    CancelToken token, int connectTimeout, int readTimeout,
                                    int maxBytes, String bearer, boolean followRedirects)
            throws WikiException {
        return request(method, urlStr, body, contentType, userAgent, referer, origin, cookie,
                token, connectTimeout, readTimeout, maxBytes, bearer, followRedirects, 0);
    }

    private static Response request(String method, String urlStr, String body, String contentType,
                                    String userAgent, String referer, String origin, String cookie,
                                    CancelToken token, int connectTimeout, int readTimeout,
                                    int maxBytes, String bearer, boolean followRedirects, int hop)
            throws WikiException {
        if (token != null && token.isCancelled()) {
            throw new WikiException(WikiException.Kind.CANCELLED, "请求已取消");
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            if (token != null) {
                token.connection = conn;
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(followRedirects);
            conn.setRequestProperty("Accept", "application/json");
            if (userAgent != null) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            if (origin != null) {
                conn.setRequestProperty("Origin", origin);
            }
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (bearer != null && !bearer.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType == null
                        ? "application/x-www-form-urlencoded; charset=UTF-8" : contentType);
                byte[] bytes = body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream out = conn.getOutputStream();
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int code = conn.getResponseCode();
            List<String> setCookies = collectSetCookies(conn.getHeaderFields());
            if (code >= 300 && code < 400) {
                // 关闭自动跳转后才会走到这里：跳转响应本身可能就带着登录凭据（MUSIC_U），
                // 因此先把这一跳的 Set-Cookie 收好，再手动跟一跳。
                String location = conn.getHeaderField("Location");
                if (location == null || location.trim().isEmpty() || hop >= MAX_REDIRECT_HOPS) {
                    return new Response("", setCookies, code);
                }
                Response next = request("POST".equals(method) ? "GET" : method,
                        resolveLocation(urlStr, location), null, null, userAgent, referer, origin,
                        cookieForRedirect(urlStr, location, cookie, setCookies), token,
                        connectTimeout, readTimeout,
                        maxBytes, null, false, hop + 1);
                setCookies.addAll(next.setCookies);
                return new Response(next.body, setCookies, next.statusCode);
            }
            if (code < 200 || code >= 300) {
                throw new WikiException(WikiException.Kind.HTTP, "HTTP " + code, code, null);
            }

            String responseBody = readBounded(conn.getInputStream(), maxBytes, token);
            return new Response(responseBody, setCookies, code);
        } catch (WikiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new WikiException(WikiException.Kind.TIMEOUT, "请求超时", e);
        } catch (UnknownHostException e) {
            throw new WikiException(WikiException.Kind.NO_NETWORK, "无法连接服务器", e);
        } catch (Exception e) {
            if (token != null && token.isCancelled()) {
                throw new WikiException(WikiException.Kind.CANCELLED, "请求已取消", e);
            }
            throw new WikiException(WikiException.Kind.NO_NETWORK, "网络异常：" + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (token != null) {
                token.connection = null;
            }
        }
    }

    /** 汇总响应中的全部 Set-Cookie 头（HTTP 规范允许同名头多次出现）。 */
    private static List<String> collectSetCookies(Map<String, List<String>> headerFields) {
        List<String> out = new ArrayList<>();
        if (headerFields == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String name = entry.getKey();
            if (name == null || !"set-cookie".equalsIgnoreCase(name)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values != null) {
                out.addAll(values);
            }
        }
        return out;
    }

    /** 相对 Location 还原成绝对地址（服务端有时只给 {@code /}）。 */
    private static String resolveLocation(String baseUrl, String location) {
        try {
            return new java.net.URL(new java.net.URL(baseUrl), location).toString();
        } catch (Exception e) {
            return location;
        }
    }

    /**
     * 跳转时是否把 Cookie 带到下一跳。
     *
     * <p>此前无条件转发：一旦服务端下发跨域 Location（配置错误、被劫持或接入中转域名），
     * MUSIC_U 就会随 Cookie 头发给第三方主机。这里按站点后缀（末两段）判定，
     * 网易云的登录跳转都在 *.163.com 内，正常流程不受影响；跨站点的跳转则不再携带凭据。
     */
    private static String cookieForRedirect(String fromUrl, String location, String cookie,
                                            List<String> setCookies) {
        String merged = mergeCookie(cookie, setCookies);
        String fromSite = siteOf(hostOf(fromUrl));
        String toSite = siteOf(hostOf(resolveLocation(fromUrl, location)));
        if (fromSite == null || !fromSite.equals(toSite)) {
            return null;
        }
        return merged;
    }

    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /** 站点后缀：取域名末两段（music.163.com → 163.com）；IP 或单段域名原样返回。 */
    private static String siteOf(String host) {
        if (host == null) {
            return null;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return host;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /** 把跳转响应下发的 Cookie（只取 name=value）并入下一次请求的 Cookie 头。 */
    private static String mergeCookie(String cookie, List<String> setCookies) {
        StringBuilder sb = new StringBuilder(cookie == null ? "" : cookie.trim());
        for (String header : setCookies) {
            if (header == null) {
                continue;
            }
            int semi = header.indexOf(';');
            String pair = (semi >= 0 ? header.substring(0, semi) : header).trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            if (name.isEmpty() || sb.indexOf(name + "=") >= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(pair);
        }
        return sb.toString();
    }

    /** 有上限地读取响应体，超限抛 TOO_LARGE。 */
    private static String readBounded(InputStream in, int maxBytes, CancelToken token)
            throws WikiException {
        if (in == null) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int len;
            while ((len = is.read(buf)) != -1) {
                if (token != null && token.isCancelled()) {
                    throw new WikiException(WikiException.Kind.CANCELLED, "请求已取消");
                }
                total += len;
                if (total > maxBytes) {
                    throw new WikiException(WikiException.Kind.TOO_LARGE, "响应体过大");
                }
                out.write(buf, 0, len);
            }
            return out.toString("UTF-8");
        } catch (WikiException e) {
            throw e;
        } catch (Exception e) {
            if (token != null && token.isCancelled()) {
                throw new WikiException(WikiException.Kind.CANCELLED, "请求已取消", e);
            }
            throw new WikiException(WikiException.Kind.NO_NETWORK, "读取响应失败：" + e.getMessage(), e);
        }
    }
}
