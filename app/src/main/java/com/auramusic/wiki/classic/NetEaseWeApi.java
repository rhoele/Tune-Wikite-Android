package com.auramusic.wiki.classic;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 WEAPI（网页版）加密协议实现。
 *
 * <p>这是「我的贡献」页面此前持续空白的根因所在：官方 {@code /api/rep/ugc/*} 系列端点
 * <b>只接受 WEAPI 加密 POST</b>，直接明文 GET 会返回 {@code {"code":301}}，
 * 界面因此走进「登录态失效」错误态。
 *
 * <p>协议流程（与原 APK {@code Lt1/m;->h} 完全一致）：
 * <ol>
 *   <li>载荷 JSON 注入 {@code csrf_token}（取自 Cookie 的 {@code __csrf}，缺失则为空串）；</li>
 *   <li>用固定密钥 {@value #AES_KEY} / IV {@value #AES_IV} 做一次 AES-CBC/PKCS5；
 *       再用随机 16 位密钥做第二次 AES-CBC/PKCS5，结果 Base64 即 {@code params}；</li>
 *   <li>把随机密钥<b>反转</b>后当作大端整数，用 RSA 公钥（指数 0x10001）做 {@code modPow}，
 *       十六进制左补零到 256 位即 {@code encSecKey}；</li>
 *   <li>以表单形式 POST 到 {@code https://music.163.com/weapi/<去掉 /api/ 前缀的 path>?csrf_token=xxx}。</li>
 * </ol>
 */
final class NetEaseWeApi {

    static final String BASE_URL = "https://music.163.com";
    /** 与原 APK 一致的网页端 Referer / Origin，缺少会被风控拦截 */
    static final String REFERER = BASE_URL + "/st/wiki/";
    static final String ORIGIN = BASE_URL;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36";

    private static final String API_PREFIX = "/api/";
    private static final String WEAPI_BASE = BASE_URL + "/weapi/";

    private static final String AES_KEY = "0CoJUm6Qyw8W8jud";
    private static final String AES_IV = "0102030405060708";
    private static final String SECRET_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final BigInteger MODULUS = new BigInteger(
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725"
                    + "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312e"
                    + "cbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d"
                    + "813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16);
    private static final BigInteger PUBLIC_EXPONENT = new BigInteger("010001", 16);
    private static final int ENC_SEC_KEY_LEN = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private NetEaseWeApi() {
    }

    /** 加密后的表单字段。 */
    static final class Payload {
        final String params;
        final String encSecKey;

        Payload(String params, String encSecKey) {
            this.params = params;
            this.encSecKey = encSecKey;
        }
    }

    /**
     * 生成一次 WEAPI 加密载荷。载荷对象会被复制，调用方传入的 JSON 不会被修改。
     */
    static Payload encrypt(JSONObject payload, String csrfToken) throws WikiException {
        try {
            JSONObject body = payload == null ? new JSONObject() : new JSONObject(payload.toString());
            body.put("csrf_token", csrfToken == null ? "" : csrfToken);
            String raw = body.toString();

            String secret = randomSecret();
            String params = aesEncrypt(aesEncrypt(raw, AES_KEY), secret);
            String encSecKey = rsaEncrypt(secret);
            return new Payload(params, encSecKey);
        } catch (WikiException e) {
            throw e;
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "WEAPI 加密失败：" + e.getMessage(), e);
        }
    }

    /** 从 Cookie 串中取 {@code __csrf} 值，不存在时返回空串（服务端仍可处理）。 */
    static String csrfToken(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        for (String segment : cookie.split(";")) {
            String s = segment.trim();
            if (s.startsWith("__csrf=")) {
                return s.substring("__csrf=".length()).trim();
            }
        }
        return "";
    }

    /**
     * 构造请求 Cookie 头：原 APK 会固定前置 {@code os=pc; appver=2.10.13} 再拼接会话 Cookie，
     * 缺少这两个字段服务端会按「来源不明」处理。
     */
    static String cookieHeader(String storedCookie) {
        String base = "os=pc; appver=2.10.13";
        if (storedCookie == null || storedCookie.trim().isEmpty()) {
            return base;
        }
        return base + "; " + storedCookie.trim();
    }

    /**
     * 发起一次 WEAPI 加密 POST，返回服务端 JSON。
     *
     * @param apiPath 形如 {@code /api/rep/ugc/detail} 的原始路径
     * @param payload 业务参数（不含 csrf_token）
     * @param cookie  本地保存的会话 Cookie（不含 os/appver）
     */
    static JSONObject post(String apiPath, JSONObject payload, String cookie, Http.CancelToken token)
            throws WikiException {
        return parse(postResponse(apiPath, payload, cookie, token).body);
    }

    /** 与 {@link #post} 相同，但保留响应头（扫码登录要读取 Set-Cookie）。 */
    static Http.Response postResponse(String apiPath, JSONObject payload, String cookie,
                                      Http.CancelToken token) throws WikiException {
        return postResponse(apiPath, payload, cookie, REFERER, token);
    }

    /** 指定 Referer 的 WEAPI POST：登录类端点需使用站点根路径而非 wiki 路径。 */
    static Http.Response postResponse(String apiPath, JSONObject payload, String cookie,
                                      String referer, Http.CancelToken token)
            throws WikiException {
        return postResponse(apiPath, payload, cookie, referer, token, true);
    }

    /**
     * 指定 Referer 的 WEAPI POST，可选择是否自动跟随 3xx 跳转。
     *
     * <p>扫码登录必须在 {@code followRedirects=false} 下调用：登录成功后
     * {@code client/login} 会用跳转响应的 Set-Cookie 下发 {@code MUSIC_U}，
     * 自动跟随会把这份凭据连同中间响应一起丢掉。
     */
    static Http.Response postResponse(String apiPath, JSONObject payload, String cookie,
                                      String referer, Http.CancelToken token,
                                      boolean followRedirects) throws WikiException {
        String csrf = csrfToken(cookie);
        Payload encrypted = encrypt(payload, csrf);

        String path = apiPath.startsWith(API_PREFIX)
                ? apiPath.substring(API_PREFIX.length()) : apiPath;
        String url = WEAPI_BASE + path + "?csrf_token=" + urlEncode(csrf);

        String form = "params=" + urlEncode(encrypted.params)
                + "&encSecKey=" + urlEncode(encrypted.encSecKey);

        String header = cookieHeader(cookie);
        return followRedirects
                ? Http.postFormResponse(url, form, UA, referer, ORIGIN, header, token)
                : Http.postFormResponseNoRedirect(url, form, UA, referer, ORIGIN, header, token);
    }

    // ------------------------------------------------------------------

    private static String randomSecret() {
        char[] chars = new char[16];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = SECRET_CHARS.charAt(RANDOM.nextInt(SECRET_CHARS.length()));
        }
        return new String(chars);
    }

    private static String aesEncrypt(String text, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rsaEncrypt(String secret) {
        byte[] reversed = new StringBuilder(secret).reverse().toString()
                .getBytes(StandardCharsets.UTF_8);
        BigInteger value = new BigInteger(1, reversed);
        String hex = value.modPow(PUBLIC_EXPONENT, MODULUS).toString(16);
        StringBuilder sb = new StringBuilder(ENC_SEC_KEY_LEN);
        for (int i = hex.length(); i < ENC_SEC_KEY_LEN; i++) {
            sb.append('0');
        }
        return sb.append(hex).toString();
    }

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
}
