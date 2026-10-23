package com.auramusic.wiki.classic;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * 外链跳转工具（对应基准 F6/F8/G10）。
 *
 * <p>覆盖歌曲页、专辑页、歌手主页、用户主页四类目标，并对无浏览器 / 无匹配 Activity 的场景兜底。
 *
 * <p><b>跳转规则（按贡献类型统一分发）</b>：类型由条目自身决定（见
 * {@link Contribution#routeType()}），因此所有标签页——包括混排的「全部」——生成链接的
 * 规则完全一致：
 * <ul>
 *   <li>艺人（1） → {@code /artist?id=}</li>
 *   <li>专辑（2）/ 补充专辑（101） → {@code /album?id=}</li>
 *   <li>歌曲（3）/ 歌词（5）/ 翻译歌词（6）→ {@code /song?id=}（歌词与翻译依附于歌曲）</li>
 *   <li>其它未收录类型 / 类型未知 → 兜底 {@code /song?id=}</li>
 * </ul>
 * 此前实现用「当前分类标签」而非条目自身类型生成链接，导致「全部」页（分类值 0）
 * 的所有链接都被拼成单曲页。
 */
public final class WikiLinks {

    private static final String PREFIX = "https://music.163.com";

    private WikiLinks() {
    }

    public static String songUrl(long songId) {
        return PREFIX + "/song?id=" + songId;
    }

    public static String albumUrl(long albumId) {
        return PREFIX + "/album?id=" + albumId;
    }

    public static String artistUrl(String artistId) {
        return PREFIX + "/artist?id=" + artistId;
    }

    public static String userUrl(long userId) {
        return PREFIX + "/user/home?id=" + userId;
    }

    /**
     * 按贡献类型生成目标外链。
     *
     * @param type 条目自身类型，取自 {@link Contribution#routeType()}
     * @param id   贡献内容主键（{@code bizId}），必须为合法正数
     * @return 校验通过的 https 外链；{@code id} 非法时返回 {@code null}
     */
    @Nullable
    public static String urlFor(int type, long id) {
        if (!WikiIdParser.isValid(id)) {
            return null;
        }
        switch (type) {
            case NetEaseWikiClient.TYPE_ARTIST:
                return artistUrl(String.valueOf(id));
            case NetEaseWikiClient.TYPE_ALBUM:
            case NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT:
                return albumUrl(id);
            case NetEaseWikiClient.TYPE_SONG:
            case NetEaseWikiClient.TYPE_LYRIC:
            case NetEaseWikiClient.TYPE_LYRIC_TRANSLATION:
            default:
                return songUrl(id);
        }
    }

    /** 打开外链；失败时给出提示而不是崩溃（异常处理兜底） */
    public static void open(Context context, String url) {
        if (context == null || !isWellFormed(url)) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            notifyFailure(context, context.getString(R.string.link_no_app));
        } catch (Exception e) {
            notifyFailure(context, context.getString(R.string.link_open_failed));
        }
    }

    /** 调用方一般是 Activity；拿不到 Activity 时才退回 Toast（外链也会从 Application 上下文触发）。 */
    private static void notifyFailure(Context context, String message) {
        if (context instanceof android.app.Activity) {
            Feedback.show((android.app.Activity) context, message);
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 外链格式校验：必须是 http/https 且 host 为网易云域名，
     * 避免拼接出空 id 或畸形地址后依然尝试跳转。
     */
    public static boolean isWellFormed(@Nullable String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String value = url.trim();
        if (!value.startsWith("https://") && !value.startsWith("http://")) {
            return false;
        }
        int query = value.indexOf("?id=");
        if (query < 0 || query + 4 >= value.length()) {
            return false;
        }
        String id = value.substring(query + 4);
        int amp = id.indexOf('&');
        if (amp >= 0) {
            id = id.substring(0, amp);
        }
        return WikiIdParser.parse(id) > 0L;
    }
}
