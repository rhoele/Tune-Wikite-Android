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
