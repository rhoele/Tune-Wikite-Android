package com.auramusic.wiki.classic;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌曲 ID / 分享链接归一化解析（对应基准 F2 / G4）。
 *
 * 纯函数实现，无 Android 依赖，便于单元测试覆盖。
 * 支持三种输入形态：
 *   1. 纯数字歌曲 ID，如 {@code 347230}
 *   2. 网易云分享链接，如 {@code https://music.163.com/song?id=347230&userid=1}
 *   3. 分享口令文本中内嵌的链接，如 {@code 分享单曲: http://163cn.tv/xxx (来自@网易云音乐)}
 */
public final class WikiIdParser {

    /** 合法 ID：首位非 0，长度 1~20（基准同款约束） */
    private static final Pattern PURE_ID = Pattern.compile("^[1-9][0-9]{0,19}$");

    /** 从链接或文本中提取 ID：兼容 ?id= / &#id= / &id= / songId= */
    private static final Pattern LINK_ID =
            Pattern.compile("(?:[?&#]id=|songId=)([1-9][0-9]{0,19})(?![0-9])");

    /** 兜底：任意 6 位以上数字串（分享口令中常见纯 ID 片段） */
    private static final Pattern LOOSE_ID = Pattern.compile("(?<![0-9])([1-9][0-9]{5,19})(?![0-9])");

    /**
     * 艺人分享链接：按 host / path / query / 哈希路由结构解析，而不是字符串包含「artist」。
     *
     * <p>兼容：
     * <ul>
     *   <li>{@code https://y.music.163.com/m/artist?id=56949793}</li>
     *   <li>{@code https://music.163.com/#/artist?id=56949793}（路径与参数都在 fragment 里）</li>
     *   <li>{@code https://music.163.com/artist?id=56949793}</li>
     *   <li>完整的「分享歌手」口令：文本中夹带链接、换行、空格与其它 query 参数</li>
     * </ul>
     *
     * <p>歌曲 / 专辑 / 歌单链接一律不识别；文本中有多个链接时取第一个可用的艺人链接。
     */
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s\"'<>，。；、）)]+");

    /** 纯数字艺人 ID（与歌曲 ID 相同的合法性约束） */
    private static final Pattern PURE_ARTIST_ID = Pattern.compile("^[1-9][0-9]{0,19}$");

    private WikiIdParser() {
    }

    /**
     * 解析歌曲 ID。
     *
     * @param raw 用户输入或剪贴板文本
     * @return 解析出的歌曲 ID；无法解析时返回 -1
     */
    public static long parse(String raw) {
        if (raw == null) {
            return -1L;
        }
        String input = raw.trim();
        if (input.isEmpty()) {
            return -1L;
        }

        // 1. 纯数字
        if (PURE_ID.matcher(input).matches()) {
            return safeParse(input);
        }

        // 2. 链接参数精确匹配（优先级高于兜底）
        Matcher link = LINK_ID.matcher(input);
        if (link.find()) {
            return safeParse(link.group(1));
        }

        // 3. 文本中扫描数字片段
        //    注意：仅在输入含分隔符（空格/换行/标点/URL）时才启用兜底，
        //    避免把「347230abc」这类非法输入误判为有效 ID。
        if (containsDelimiter(input)) {
            Matcher loose = LOOSE_ID.matcher(input);
            if (loose.find()) {
                return safeParse(loose.group(1));
            }
        }
        return -1L;
    }

    /** 是否为可直接使用的合法 ID */
    public static boolean isValid(long id) {
        return id > 0L;
    }

    /**
     * 从艺人分享文本中解析艺人 ID。
     *
     * @param raw 用户粘贴的分享口令、艺人链接或纯数字 ID
     * @return 艺人 ID；文本中没有可识别的艺人链接时返回 -1
     */
    public static long parseArtistId(String raw) {
        if (raw == null) {
            return -1L;
        }
        String input = raw.trim();
        if (input.isEmpty()) {
            return -1L;
        }
        if (PURE_ARTIST_ID.matcher(input).matches()) {
            return safeParse(input);
        }
        Matcher urls = URL_PATTERN.matcher(input);
        while (urls.find()) {
            long id = artistIdFromUrl(urls.group());
            if (id > 0L) {
                return id;
            }
        }
        return -1L;
    }

    /** 从单条 URL 中判定是否为网易云艺人链接，并取出 {@code id} 参数。 */
    private static long artistIdFromUrl(String rawUrl) {
        String url = trimTrailingPunctuation(rawUrl);
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (host == null) {
                return -1L;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("music.163.com") && !host.endsWith(".music.163.com")) {
                return -1L;
            }
            String path = uri.getPath();
            String query = uri.getRawQuery();
            // 哈希路由：#/artist?id=xxx —— 路径与参数都在 fragment 里
            String fragment = uri.getRawFragment();
            if (fragment != null && !fragment.isEmpty()) {
                java.net.URI inner = new java.net.URI(
                        fragment.startsWith("/") ? fragment : "/" + fragment);
                path = inner.getPath();
                query = inner.getRawQuery();
            }
            if (path == null) {
                return -1L;
            }
            String normalized = path.toLowerCase(java.util.Locale.ROOT);
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            // 只接受艺人页；歌曲 / 专辑 / 歌单 / 电台等路径一律忽略
            if (!normalized.equals("/artist") && !normalized.equals("/m/artist")) {
                return -1L;
            }
            return idFromQuery(query);
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 从 query 串里取 {@code id}，并校验为合法 ID。 */
    private static long idFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return -1L;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!"id".equals(pair.substring(0, eq))) {
                continue;
            }
            String value = pair.substring(eq + 1);
            if (PURE_ARTIST_ID.matcher(value).matches()) {
                return safeParse(value);
            }
        }
        return -1L;
    }

    /** 去掉链接末尾可能被一起复制的标点，避免 {@code id=123，} 这类尾巴导致解析失败。 */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (c == '.' || c == ',' || c == ')' || c == ']' || c == '}'
                    || c == '，' || c == '。' || c == '、' || c == '）' || c == '】') {
                end--;
                continue;
            }
            break;
        }
        return url.substring(0, end);
    }

    /** 归一化输出：解析失败返回 null，成功返回数字字符串 */
    public static String normalize(String raw) {
        long id = parse(raw);
        return id > 0 ? String.valueOf(id) : null;
    }

    private static boolean containsDelimiter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    private static long safeParse(String digits) {
        try {
            long v = Long.parseLong(digits);
            return v > 0L ? v : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
