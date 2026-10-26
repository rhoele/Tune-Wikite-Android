package com.auramusic.wiki.classic;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 EAPI（App 通道）加密协议实现，与原 APK {@code Lt1/m;->e} / {@code Lt1/m;->d} 一致。
 *
 * <p>协议流程：
 * <ol>
 *   <li>构造 {@code header}（os / appver / osver / deviceId / versioncode / buildver /
 *       resolution / requestId / channel / __csrf / MUSIC_U）并以<b>字符串</b>形式塞进业务载荷的
 *       {@code header} 字段；</li>
 *   <li>{@code data = 载荷.toString()}；
 *       {@code digest = md5("nobody" + apiPath + "use" + data + "md5forencrypt")}（小写十六进制）；</li>
 *   <li>{@code value = apiPath + "-36cd479b6b5-" + data + "-36cd479b6b5-" + digest}，
 *       用固定密钥 {@value #AES_KEY_TEXT} 做 AES/ECB/PKCS5Padding 后转<b>大写十六进制</b>
 *       即 {@code params}（参考实现 {@code LX1/q} 的 {@code %02X}）；</li>
 *   <li>以表单形式 POST 到 {@code https://interface3.music.163.com/eapi/<去掉 /api 前缀的 path>}。</li>
 * </ol>
 *
 * <p>与 WEAPI 的差别：eapi 无需 RSA，Cookie 走 {@code os=android} 前缀，且响应体同样是明文 JSON。
 */
final class NetEaseEapi {

    static final String BASE_URL = "https://interface3.music.163.com/eapi";
    /** 与原 APK 一致的 App 端 UA，缺少会被风控按「网页来源」拦截 */
    static final String UA = "NeteaseMusic/8.20.20.240716 (Linux; Android 14)";
    static final String REFERER = "https://music.163.com/";

    private static final String API_PREFIX = "/api";

    private static final String AES_KEY_TEXT = "e82ckenh8dichen8";
    private static final byte[] AES_KEY = AES_KEY_TEXT.getBytes(StandardCharsets.UTF_8);
    private static final String SEP = "-36cd479b6b5-";
    private static final String DIGEST_HEAD = "nobody";
    private static final String DIGEST_MID = "use";
    private static final String DIGEST_TAIL = "md5forencrypt";

    private static final String OS = "android";
    private static final String APP_VER = "8.20.20";
    private static final String OS_VER = "14";
    private static final String VERSION_CODE = "8200";
    private static final String RESOLUTION = "1080x2400";
    private static final String CHANNEL = "xiaomi";

    private NetEaseEapi() {
    }

    /**
     * 发起一次 EAPI 加密 POST，返回服务端 JSON。
     *
     * @param apiPath  形如 {@code /api/rep/ugc/detail} 的原始路径
     * @param payload  业务参数
     * @param deviceId 设备标识（首次使用生成并持久化，见 {@link WikiPrefs#deviceId})
     * @param cookie   本地保存的会话 Cookie
     */
    static JSONObject post(String apiPath, JSONObject payload, String deviceId, String cookie,
                           Http.CancelToken token) throws WikiException {
        String params = encrypt(apiPath, payload, deviceId, cookie);
        // 参考实现拼的是 "https://interface3.music.163.com/e" + apiPath + "/"，
        // 与 eapi 前缀等价；末尾斜杠保持一致，避免个别网关按严格路径匹配时 404。
        String url = BASE_URL + (apiPath.startsWith(API_PREFIX)
                ? apiPath.substring(API_PREFIX.length()) : apiPath) + "/";

        String body = Http.postForm(url, "params=" + urlEncode(params), UA, REFERER, null,
                cookieHeader(deviceId, cookie), token);
        return parse(body);
    }

    // ------------------------------------------------------------------

    /** 供单测校验报文格式；生产路径请走 {@link #post}。 */
    static String encrypt(String apiPath, JSONObject payload, String deviceId, String cookie)
            throws WikiException {
        try {
            JSONObject body = payload == null ? new JSONObject() : new JSONObject(payload.toString());

            long now = System.currentTimeMillis();
            JSONObject header = new JSONObject();
            header.put("os", OS);
            header.put("appver", APP_VER);
            header.put("osver", OS_VER);
            header.put("deviceId", deviceId);
            header.put("versioncode", VERSION_CODE);
            header.put("buildver", String.valueOf(now / 1000L));
            header.put("resolution", RESOLUTION);
            header.put("requestId", now + "_" + padZero(now % 1000L, 4));
            header.put("channel", CHANNEL);
            header.put("__csrf", cookieValue(cookie, "__csrf", ""));
            String musicU = cookieValue(cookie, "MUSIC_U", null);
            if (musicU != null) {
                header.put("MUSIC_U", musicU);
            }
            body.put("header", header.toString());

            String data = body.toString();
            String digest = md5Hex(DIGEST_HEAD + apiPath + DIGEST_MID + data + DIGEST_TAIL);
            String value = apiPath + SEP + data + SEP + digest;

            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            return hexUpper(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "EAPI 加密失败：" + e.getMessage(), e);
        }
    }

    /** 密文转大写十六进制：App 通道的 {@code params} 不是 Base64，用 Base64 会被服务端直接判为非法。 */
    private static String hexUpper(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(digits[(b >> 4) & 0xF]).append(digits[b & 0xF]);
        }
        return sb.toString();
    }

    /**
     * 构造请求 Cookie 头：原 APK 固定前置 {@code os=android; appver=...; deviceId=...; channel=xiaomi}，
     * 再按需追加 {@code MUSIC_U} 与 {@code __csrf}。
     */
    private static String cookieHeader(String deviceId, String cookie) {
        StringBuilder sb = new StringBuilder("os=").append(OS)
                .append("; appver=").append(APP_VER)
                .append("; deviceId=").append(deviceId)
                .append("; channel=").append(CHANNEL);
        String musicU = cookieValue(cookie, "MUSIC_U", null);
        if (musicU != null) {
            sb.append("; MUSIC_U=").append(musicU);
        }
        String csrf = cookieValue(cookie, "__csrf", "");
        if (!csrf.isEmpty()) {
            sb.append("; __csrf=").append(csrf);
        }
        return sb.toString();
    }

    /** 从 Cookie 串中取值，缺失时返回 {@code fallback}。 */
    private static String cookieValue(String cookie, String name, String fallback) {
        if (cookie == null || cookie.isEmpty()) {
            return fallback;
        }
        String prefix = name + "=";
        for (String segment : cookie.split(";")) {
            String s = segment.trim();
            if (s.startsWith(prefix)) {
                return s.substring(prefix.length()).trim();
            }
        }
        return fallback;
    }

    private static String md5Hex(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String padZero(long value, int length) {
        String s = Long.toString(value);
        StringBuilder sb = new StringBuilder(length);
        for (int i = s.length(); i < length; i++) {
            sb.append('0');
        }
        return sb.append(s).toString();
    }

    /**
     * 解析 EAPI 响应体。
     *
     * <p>App 通道的响应体在多数情况下是明文 JSON，但实测存在两类非规范返回：
     * <ul>
     *   <li>带 UTF-8 BOM / 前后空白（部分 CDN 节点会补字节序标记）；</li>
     *   <li>JSON 前后夹带了网关追加的提示文本（如限流提示），此时直接
     *       {@code new JSONObject(body)} 会抛异常，被上层误判为「数据格式异常」。</li>
     * </ul>
     * 因此这里先做 BOM / 空白清理，再在必要时截取最外层的 {@code { ... }} 子串解析，
     * 尽最大努力把可用的 JSON 取出来；确实无法解析时才抛 {@code BAD_JSON}。
     */
    private static JSONObject parse(String body) throws WikiException {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "响应体为空");
        }
        JSONObject direct = tryParse(text);
        if (direct != null) {
            return direct;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JSONObject sliced = tryParse(text.substring(start, end + 1));
            if (sliced != null) {
                android.util.Log.w("NetEaseEapi",
                        "响应体夹带非 JSON 前缀/后缀，已截取 JSON 主体解析；body=" + brief(text));
                return sliced;
            }
        }
        throw new WikiException(WikiException.Kind.BAD_JSON, "响应不是合法 JSON：" + brief(text));
    }

    private static JSONObject tryParse(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 日志截断，避免把整段响应体打进日志。 */
    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
