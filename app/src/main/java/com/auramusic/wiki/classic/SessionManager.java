package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.Locale;

/**
 * 登录会话管理（对应需求「登录状态持久化存储」）。
 *
 * 设计要点：
 *   - Cookie 复用 {@link WikiPrefs#KEY_COOKIE} 作为唯一键，保证「我的贡献」「后台轮询」
 *     与登录模块读写同一份凭据，不会出现两处状态不一致；
 *   - 昵称 / 头像 / 用户 ID / 登录方式 / 登录时间一并持久化，冷启动即可直接渲染账号信息；
 *   - 所有写入均为同步 {@code commit()}，避免登录后立即跳转时读到旧值。
 */
public final class SessionManager {

    public static final String KEY_NICKNAME = "session_nickname";
    public static final String KEY_USER_ID = "session_user_id";
    public static final String KEY_AVATAR = "session_avatar";
    public static final String KEY_LOGIN_TIME = "session_login_time";
    public static final String KEY_LOGIN_TYPE = "session_login_type";

    /** 登录方式 */
    public static final String TYPE_QR = "qr";
    public static final String TYPE_COOKIE = "cookie";
    /** 网易云官方网页登录（手机号 + 验证码 / 密码） */
    public static final String TYPE_WEB = "web";

    /** 网易云鉴权核心 Cookie 名，缺失即视为无效凭据 */
    public static final String CORE_COOKIE = "MUSIC_U";

    private SessionManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return WikiPrefs.get(context);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public static String cookie(Context context) {
        return WikiPrefs.cookie(context);
    }

    public static boolean isLoggedIn(Context context) {
        return !TextUtils.isEmpty(cookie(context));
    }

    public static String nickname(Context context) {
        String v = prefs(context).getString(KEY_NICKNAME, "");
        return v == null ? "" : v.trim();
    }

    public static long userId(Context context) {
        return prefs(context).getLong(KEY_USER_ID, 0L);
    }

    public static String avatarUrl(Context context) {
        String v = prefs(context).getString(KEY_AVATAR, "");
        return v == null ? "" : v.trim();
    }

    public static long loginTime(Context context) {
        return prefs(context).getLong(KEY_LOGIN_TIME, 0L);
    }

    public static String loginType(Context context) {
        String v = prefs(context).getString(KEY_LOGIN_TYPE, "");
        return v == null ? "" : v.trim();
    }

    /** 无昵称时回退到用户 ID，再回退到通用文案（由 UI 层决定最终展示）。 */
    public static String displayName(Context context) {
        String n = nickname(context);
        if (!n.isEmpty()) {
            return n;
        }
        long id = userId(context);
        return id > 0 ? String.valueOf(id) : "";
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    public static void save(Context context, String rawCookie, NetEaseAuthClient.Profile profile, String loginType) {
        String cookie = sanitizeCookie(rawCookie);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(WikiPrefs.KEY_COOKIE, cookie)
                .putString(KEY_LOGIN_TYPE, loginType)
                .putLong(KEY_LOGIN_TIME, System.currentTimeMillis());
        if (profile != null) {
            editor.putString(KEY_NICKNAME, profile.nickname == null ? "" : profile.nickname)
                    .putLong(KEY_USER_ID, profile.userId)
                    .putString(KEY_AVATAR, profile.avatarUrl == null ? "" : profile.avatarUrl);
        }
        editor.commit();
    }

    /** 仅更新 Cookie（用于旧版「粘贴 Cookie」入口的兼容路径）。 */
    public static void saveCookieOnly(Context context, String rawCookie) {
        prefs(context).edit()
                .putString(WikiPrefs.KEY_COOKIE, sanitizeCookie(rawCookie))
                .putString(KEY_LOGIN_TYPE, TYPE_COOKIE)
                .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
                .commit();
    }

    /**
     * 清理账号维度的本地数据（审核收件箱、未通过原因归档、轮询去重集合）。
     *
     * <p>只在「用户主动退出登录 / 切换账号」时调用。登录失效（401）同样会走
     * {@link #clear}，但那种情况下清空归档会让用户无故丢失历史记录，故不在这里触发。
     */
    public static void clearAccountData(Context context) {
        try {
            AuditInbox.clearAll(context);
            RejectArchive.clearAll(context);
            prefs(context).edit().remove(WikiPrefs.KEY_DONE_IDS).apply();
        } catch (Exception ignored) {
            // 清理失败不影响退出登录本身
        }
    }

    public static void clear(Context context) {
        prefs(context).edit()
                .remove(WikiPrefs.KEY_COOKIE)
                .remove(KEY_NICKNAME)
                .remove(KEY_USER_ID)
                .remove(KEY_AVATAR)
                .remove(KEY_LOGIN_TIME)
                .remove(KEY_LOGIN_TYPE)
                .commit();
        // 退出登录后解绑推送：避免下一位登录者收到上一位账号的消息（隐私合规）
        FcmTokenUploader.unregister(context);
    }

    // ------------------------------------------------------------------
    // Cookie 规整与校验（对应需求「完善的输入校验」）
    // ------------------------------------------------------------------

    /**
     * 规整用户粘贴的 Cookie。兼容以下常见粘贴形态：
     * <ul>
     *   <li>完整 Cookie 头（可带 {@code Cookie:} 前缀、引号、换行 / 制表符 / 中文分号）；</li>
     *   <li>浏览器导出的 JSON（{@code {"MUSIC_U":"..."}}）；</li>
     *   <li>整串被 URL 编码（{@code MUSIC_U%3D...%3B%20__csrf%3D...}）；</li>
     *   <li><b>只粘贴 MUSIC_U 的值</b>（无 {@code =} 的裸 token）；</li>
     *   <li>带 {@code Path / Expires / Max-Age / Domain} 等属性的 Set-Cookie 文本。</li>
     * </ul>
     * 输出统一为单行 {@code k=v; k=v}，并剔除空段、无值段与 Cookie 属性。
     */
    public static String sanitizeCookie(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\uFEFF", "").replace("\u200B", "").trim();
        if (s.isEmpty()) {
            return "";
        }
        // 整串被引号包裹（从某些脚本 / JSON 字符串里复制）
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // JSON 形态的凭据
        if (s.startsWith("{")) {
            String fromJson = fromJsonCookie(s);
            if (!fromJson.isEmpty()) {
                return fromJson;
            }
        }
        // 换行 / 制表符 / 中文分号统一为英文分号
        s = s.replace('\n', ';').replace('\r', ';').replace('\t', ' ')
                .replace('；', ';');

        int colon = s.indexOf(':');
        if (colon > 0) {
            String head = s.substring(0, colon).trim();
            if ("cookie".equalsIgnoreCase(head)) {
                s = s.substring(colon + 1).trim();
                // 前缀与引号可能同时出现：Cookie: "MUSIC_U=..."
                if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                        || (s.startsWith("'") && s.endsWith("'")))) {
                    s = s.substring(1, s.length() - 1).trim();
                }
            }
        }
        // 整串 URL 编码（'=' 与 ';' 被编码成 %3D / %3B）
        if (s.indexOf('=') < 0 && (s.toLowerCase(Locale.ROOT).contains("%3d"))) {
            try {
                s = java.net.URLDecoder.decode(s, "UTF-8");
            } catch (Exception ignored) {
                // 解码失败时保留原文，后续按裸 token 处理
            }
        }
        // 只粘贴了 MUSIC_U 的值：没有 '=' 也没有分号时按核心 Cookie 处理
        if (s.indexOf('=') < 0 && s.indexOf(';') < 0) {
            String token = s.trim();
            if (token.length() >= 8) {
                return CORE_COOKIE + "=" + token;
            }
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String part : s.split(";")) {
            String seg = part.trim();
            int eq = seg.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = seg.substring(0, eq).trim();
            String value = seg.substring(eq + 1).trim();
            // 去掉值两侧可能残留的引号
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (key.isEmpty() || value.isEmpty() || isCookieAttribute(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(key).append('=').append(value);
        }
        return sb.toString();
    }

    private static boolean isCookieAttribute(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "path":
            case "expires":
            case "max-age":
            case "domain":
            case "secure":
            case "httponly":
            case "samesite":
            case "priority":
            case "partitioned":
                return true;
            default:
                return false;
        }
    }

    private static String fromJsonCookie(String json) {
        try {
            org.json.JSONObject object = new org.json.JSONObject(json);
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key, "").trim();
                if (value.isEmpty() || isCookieAttribute(key)) {
                    continue;
                }
                // 兼容 {"cookie":"MUSIC_U=...; __csrf=..."} 这类嵌套导出
                if ("cookie".equalsIgnoreCase(key) && value.indexOf('=') > 0) {
                    String nested = sanitizeCookie(value);
                    if (!nested.isEmpty()) {
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(nested);
                    }
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(key).append('=').append(value);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 是否包含核心鉴权 Cookie（网易云为 MUSIC_U）。 */
    public static boolean looksValid(String rawCookie) {
        String c = sanitizeCookie(rawCookie);
        return c.contains(CORE_COOKIE + "=");
    }

    /** 校验失败时的原因（用于输入框错误提示），合法时返回 null。 */
    public static String validateReason(String rawCookie) {
        String c = sanitizeCookie(rawCookie);
        if (c.isEmpty()) {
            return "empty";
        }
        if (!c.contains(CORE_COOKIE + "=")) {
            return "missing_core";
        }
        int idx = c.indexOf(CORE_COOKIE + "=");
        String value = c.substring(idx + CORE_COOKIE.length() + 1);
        int end = value.indexOf(';');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        if (value.trim().length() < 8) {
            return "malformed";
        }
        return null;
    }

    /** 从 Set-Cookie 头列表拼出可用的 Cookie 串（只取 name=value）。 */
    public static String fromSetCookies(java.util.List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String header : setCookies) {
            if (header == null) {
                continue;
            }
            int semi = header.indexOf(';');
            String pair = (semi >= 0 ? header.substring(0, semi) : header).trim();
            if (pair.indexOf('=') <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(pair);
        }
        return sb.toString();
    }
}
