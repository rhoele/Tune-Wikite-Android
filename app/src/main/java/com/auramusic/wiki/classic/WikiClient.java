package com.auramusic.wiki.classic;

/**
 * 歌曲百科客户端：对接免登录聚合接口，返回完整曲库资料与公开贡献记录。
 * 对应基准 F1/F11/F18/G18：具备业务 UA 标识、超时分级、响应体上限与可取消能力。
 */
public final class WikiClient {

    /** 免登录聚合接口（基准同源后端） */
    private static final String ENDPOINT = "https://ne.cdgz.top/api/contribution";

    /** 业务 UA 标识（基准 F11 / G18） */
    private static final String UA = "WikiUploader/1.1.2 (Android; PublicWiki)";

    private static final String REFERER = "https://music.163.com/";

    private WikiClient() {
    }

    /**
     * 查询指定歌曲的百科数据。
     *
     * @param songId 已归一化的歌曲 ID
     * @param token  可取消句柄，可为 null
     * @throws WikiException 各类网络/解析异常
     */
    public static SongWiki fetch(long songId, Http.CancelToken token) throws WikiException {
        String url = ENDPOINT + "?id=" + songId;
        String body = Http.get(url, UA, REFERER, null, token);
        return SongWiki.fromJson(body);
    }
}
