package com.auramusic.wiki.classic;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 网易云登录鉴权客户端（需求 2：还原完整登录流程）。
 *
 * 覆盖三种能力：
 *   1. Web 端二维码登录：{@code /api/login/qrcode/unikey} 取 key → 轮询
 *      {@code /api/login/qrcode/client/login}（801 待扫码 / 802 已扫码 / 803 确认 / 800 过期），
 *      803 时从响应头 Set-Cookie 与响应体 cookie 字段双重提取会话；
 *   2. Cookie 登录：校验 {@code MUSIC_U} 是否可用；
 *   3. 会话有效性校验：{@code /api/nuser/account/get} 读取账号资料。
 *
 * 所有方法均支持在途取消，异常统一为 {@link WikiException} 分级，便于界面差异化提示。
 */
public final class NetEaseAuthClient {

    private static final String BASE_URL = "https://music.163.com";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0 Safari/537.36";

    private static final String REFERER = BASE_URL + "/";

    /** 二维码 key / 状态查询端点（WEAPI 与明文通道共用同一路径） */
    private static final String QR_KEY_API = "/api/login/qrcode/unikey";
    private static final String QR_CHECK_API = "/api/login/qrcode/client/login";

    /** 二维码业务码 */
    private static final int QR_EXPIRED = 800;
    private static final int QR_WAITING = 801;
    private static final int QR_SCANNED = 802;
    private static final int QR_CONFIRMED = 803;

    private NetEaseAuthClient() {
    }

    // ------------------------------------------------------------------
    // 二维码登录
    // ------------------------------------------------------------------

    /** 一次二维码会话 */
    public static final class QrSession {
        /** 轮询用的唯一 key */
        public final String key;
        /** 需要绘制成二维码的内容（网易云 App 扫码即识别该链接） */
        public final String content;
        /** unikey 接口下发的 Cookie（NMTID 等），轮询时需原样带回 */
        public final String cookies;

        QrSession(String key, String content, String cookies) {
            this.key = key;
            this.content = content;
            this.cookies = cookies == null ? "" : cookies;
        }
    }

    /** 二维码轮询状态 */
    public enum QrState {
        /** 等待扫码 */
        WAITING,
        /** 已扫码，待手机端确认 */
        SCANNED,
        /** 登录成功，cookie 已下发 */
        CONFIRMED,
        /** 二维码已过期，需要重新获取 */
        EXPIRED
    }

    /** 轮询结果 */
    public static final class QrPollResult {
        public final QrState state;
        /** 仅 CONFIRMED 时非空 */
        public final String cookie;
        /** 轮询过程中累积的 Cookie（需在下次轮询时带回） */
        public final String jar;
        /** 服务端原始提示文案（便于排查） */
        public final String message;

        QrPollResult(QrState state, String cookie, String jar, String message) {
            this.state = state;
            this.cookie = cookie == null ? "" : cookie;
            this.jar = jar == null ? "" : jar;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * 申请一个新的二维码会话。
     *
     * @throws WikiException 网络/解析异常
     */
    public static QrSession createQrSession(Http.CancelToken token) throws WikiException {
        Http.Response response = null;
        JSONObject root = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", "1");
            response = NetEaseWeApi.postResponse(QR_KEY_API, payload, "", REFERER, token);
            root = parse(response.body);
            if (root.optInt("code", -1) != 200) {
                // WEAPI 通道异常时回退明文接口，保证二维码始终能生成
                response = null;
                root = null;
            }
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            response = null;
            root = null;
        } catch (Exception ignored) {
            response = null;
            root = null;
        }
        if (response == null || root == null) {
            response = Http.getResponse(BASE_URL + "/api/login/qrcode/unikey?type=1",
                    UA, REFERER, null, token);
            root = parse(response.body);
            if (root.optInt("code", -1) != 200) {
                throw new WikiException(WikiException.Kind.EMPTY,
                        "二维码生成失败：" + root.optString("message",
                                "code=" + root.optInt("code", -1)));
            }
        }
        String key = root.optString("unikey", "").trim();
        if (key.isEmpty()) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "二维码 key 缺失");
        }
        String content = BASE_URL + "/login?codekey=" + urlEncode(key);
        String cookies = SessionManager.sanitizeCookie(
                SessionManager.fromSetCookies(response.setCookies));
        return new QrSession(key, content, cookies);
    }

    /**
     * 轮询二维码扫码状态。调用方按固定间隔（建议 2 秒）反复调用，直至返回
     * {@link QrState#CONFIRMED} 或 {@link QrState#EXPIRED}。
     *
     * <p>两条通道都<b>关闭自动跳转</b>：网易云登录成功时是用跳转响应的 Set-Cookie
     * 下发 {@code MUSIC_U} 的，交给连接层自动跟随会把这唯一的凭据丢掉，
     * 表现就是「手机已确认，App 却退回等待扫码」。跳转由 {@link Http} 手动跟随并把
     * 沿途 Cookie 累积到 {@link Http.Response#setCookies}。
     *
     * <p>优先走与原网站在同一链路上的 WEAPI POST；若服务端返回非扫码业务码
     * （加密通道被风控等），自动回退到明文接口，避免界面永远停在「等待扫码」。
     *
     * @param cookieJar 上一次轮询返回的 {@link QrPollResult#jar}；首次可传 null
     */
    public static QrPollResult pollQr(String key, String cookieJar, Http.CancelToken token)
            throws WikiException {
        if (key == null || key.trim().isEmpty()) {
            throw new WikiException(WikiException.Kind.EMPTY, "二维码 key 为空");
        }
        Http.Response response = null;
        JSONObject root = null;
        WikiException parseError = null;

        // 通道一：WEAPI 加密 POST
        try {
            JSONObject payload = new JSONObject();
            payload.put("key", key);
            payload.put("type", "1");
            response = NetEaseWeApi.postResponse(QR_CHECK_API, payload, cookieJar, REFERER,
                    token, false);
            root = tryParse(response.body);
            if (root == null) {
                parseError = new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON");
            } else {
                int code = root.optInt("code", -1);
                if (code != QR_WAITING && code != QR_SCANNED
                        && code != QR_CONFIRMED && code != QR_EXPIRED) {
                    // 加密通道给出非扫码业务码：交给明文通道重试
                    response = null;
                    root = null;
                    parseError = null;
                }
            }
        } catch (WikiException e) {
            if (e.getKind() == WikiException.Kind.CANCELLED) {
                throw e;
            }
            response = null;
            root = null;
            parseError = null;
        } catch (Exception ignored) {
            response = null;
            root = null;
            parseError = null;
        }

        // 通道二：明文接口回退（失败直接抛出，由界面提示并按节奏重试）
        if (response == null) {
            String url = BASE_URL + "/api/login/qrcode/client/login?key=" + urlEncode(key)
                    + "&type=1";
            response = Http.getResponseNoRedirect(url, UA, REFERER, cookieJar, token);
            root = tryParse(response.body);
            if (root == null) {
                parseError = new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON");
            }
        }

        // 只要这一轮（含跳转链路）下发了 MUSIC_U，就说明手机端已确认登录
        String headerCookie = SessionManager.sanitizeCookie(
                SessionManager.fromSetCookies(response.setCookies));
        String nextJar = mergeCookies(headerCookie, cookieJar);
        if (headerCookie.contains(SessionManager.CORE_COOKIE + "=")) {
            String cookie = extractSessionCookie(root, response);
            return new QrPollResult(QrState.CONFIRMED,
                    cookie.isEmpty() ? headerCookie : cookie, nextJar, "");
        }
        if (root == null) {
            // 响应既不是 JSON，也没有随跳转下发凭据：按异常交给界面提示并重试
            throw parseError == null
                    ? new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON")
                    : parseError;
        }

        int code = root.optInt("code", -1);
        String message = root.optString("message", "");
        switch (code) {
            case QR_CONFIRMED:
                String cookie = extractSessionCookie(root, response);
                if (cookie.isEmpty()) {
                    throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "扫码成功但未获取到登录凭据");
                }
                return new QrPollResult(QrState.CONFIRMED, cookie, nextJar, message);
            case QR_SCANNED:
                return new QrPollResult(QrState.SCANNED, "", nextJar, message);
            case QR_EXPIRED:
                return new QrPollResult(QrState.EXPIRED, "", nextJar, message);
            case QR_WAITING:
            default:
                return new QrPollResult(QrState.WAITING, "", nextJar, message);
        }
    }

    private static JSONObject tryParse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 双重来源提取会话：Set-Cookie 头（最权威） + 响应体 cookie 字段。
     *
     * <p>直连 music.163.com 时，803 响应可能只在 Set-Cookie 中下发凭据；
     * 而部分中转服务会把 Cookie 放进响应体，且可能是 URL 编码字符串。
     * 这里同时处理两种形态并按 key 合并，避免「扫码成功但拿不到 MUSIC_U」。
     */
    private static String extractSessionCookie(JSONObject root, Http.Response response) {
        String fromHeader = SessionManager.sanitizeCookie(
                SessionManager.fromSetCookies(response.setCookies));
        JSONObject data = root == null ? null : root.optJSONObject("data");
        String rawBody = root == null ? "" : root.optString("cookie", "");
        if (rawBody.trim().isEmpty() && data != null) {
            rawBody = data.optString("cookie", "");
        }
        String fromBody = SessionManager.sanitizeCookie(decodeCookie(rawBody));
        String merged = mergeCookies(fromHeader, fromBody);
        if (!merged.contains(SessionManager.CORE_COOKIE + "=")) {
            // 再尝试一次原始文本解码，覆盖「整串被 URL 编码」的极端情况
            String decoded = SessionManager.sanitizeCookie(decodeCookie(rawBody));
            merged = mergeCookies(merged, decoded);
        }
        return merged;
    }

    /** 整串 Cookie 被 URL 编码时先解码（'=' / ';' 会被编码成 %3D / %3B）。 */
    private static String decodeCookie(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || !text.toLowerCase(Locale.ROOT).contains("%3d")) {
            return text;
        }
        try {
            return java.net.URLDecoder.decode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    /** 合并两份 Cookie：primary 优先，secondary 只补齐 primary 中缺失的 key。 */
    private static String mergeCookies(String primary, String secondary) {
        Map<String, String> map = new LinkedHashMap<>();
        putCookiePairs(map, primary, true);
        putCookiePairs(map, secondary, false);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static void putCookiePairs(Map<String, String> map, String cookie, boolean overwrite) {
        String normalized = SessionManager.sanitizeCookie(cookie);
        if (normalized.isEmpty()) {
            return;
        }
        for (String part : normalized.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (overwrite) {
                map.put(key, value);
            } else if (!map.containsKey(key)) {
                map.put(key, value);
            }
        }
    }

    // ------------------------------------------------------------------
    // 会话校验 / 账号资料
    // ------------------------------------------------------------------

    private static String serverMessage(JSONObject root, String fallback) {
        if (root == null) {
            return fallback;
        }
        String message = root.optString("message", "");
        if (message.trim().isEmpty()) {
            message = root.optString("msg", "");
        }
        return message.trim().isEmpty() ? fallback : message.trim();
    }

    /** 账号资料 */
    public static final class Profile {
        public final long userId;
        public final String nickname;
        public final String avatarUrl;

        Profile(long userId, String nickname, String avatarUrl) {
            this.userId = userId;
            this.nickname = nickname == null ? "" : nickname;
            this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        }
    }

    /**
     * 用给定 Cookie 拉取账号资料，同时充当凭据有效性校验。
     *
     * @throws WikiException {@link WikiException.Kind#NO_CREDENTIAL} 表示 Cookie 无效或已失效
     */
    public static Profile fetchProfile(String rawCookie, Http.CancelToken token) throws WikiException {
        String cookie = SessionManager.sanitizeCookie(rawCookie);
        if (cookie.isEmpty()) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "Cookie 为空");
        }
        if (!SessionManager.looksValid(cookie)) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL,
                    "Cookie 中缺少 " + SessionManager.CORE_COOKIE);
        }

        String body = Http.get(BASE_URL + "/api/nuser/account/get", UA, REFERER,
                NetEaseWeApi.cookieHeader(cookie), token);
        JSONObject root = parse(body);

        JSONObject profile = root.optJSONObject("profile");
        JSONObject account = root.optJSONObject("account");
        if (profile == null && account == null) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "登录态无效或已过期");
        }

        long userId = 0L;
        String nickname = "";
        String avatar = "";
        if (profile != null) {
            userId = profile.optLong("userId", 0L);
            nickname = profile.optString("nickname", "");
            avatar = profile.optString("avatarUrl", "");
        }
        if (userId <= 0L && account != null) {
            userId = account.optLong("id", 0L);
        }
        if (userId <= 0L) {
            throw new WikiException(WikiException.Kind.NO_CREDENTIAL, "登录态无效或已过期");
        }
        return new Profile(userId, nickname, avatar);
    }

    // ------------------------------------------------------------------

    private static JSONObject parse(String body) throws WikiException {
        if (body == null || body.trim().isEmpty()) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON", e);
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 构造请求参数，JSONException 统一收敛为 WikiException。 */
    private static JSONObject params(Object... pairs) throws WikiException {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                object.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "参数构造失败", e);
        }
        return object;
    }
}
