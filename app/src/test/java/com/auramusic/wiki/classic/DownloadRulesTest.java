package com.auramusic.wiki.classic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * 下载失败分类的契约：
 * <ul>
 *   <li>只有真正的 403 / 503 才算 Cloudflare 挑战（否则普通失败会误开浏览器通道）；</li>
 *   <li>网页 / JSON / XML 响应不能被当成音频落盘。</li>
 * </ul>
 */
public class DownloadRulesTest {

    @Test
    public void detectsCloudflareChallenge() {
        assertTrue(LucidaClient.isChallenge(new IOException("HTTP 403：blocked")));
        assertTrue(LucidaClient.isChallenge(new IOException("HTTP 503")));
        assertTrue(LucidaClient.isChallenge(new IOException("下载失败 HTTP 403：blocked")));
    }

    @Test
    public void doesNotTreatOrdinaryFailuresAsChallenge() {
        assertFalse(LucidaClient.isChallenge(new IOException("HTTP 404：not found")));
        assertFalse(LucidaClient.isChallenge(new IOException("HTTP 500")));
        assertFalse(LucidaClient.isChallenge(new IOException("下载超时：连续 60 秒没有收到数据")));
        assertFalse(LucidaClient.isChallenge(new IOException("Unable to resolve host lucida.to")));
        assertFalse(LucidaClient.isChallenge(new IOException("已取消下载")));
        // 响应体摘要里恰好提到 403，但状态码不是 403：不能当作挑战
        assertFalse(LucidaClient.isChallenge(
                new IOException("下载失败 HTTP 500：upstream said HTTP 403")));
        assertFalse(LucidaClient.isChallenge(null));
    }

    @Test
    public void rejectsErrorPages() {
        assertFalse(CronetHttp.isDownloadableType("text/html; charset=utf-8"));
        assertFalse(CronetHttp.isDownloadableType("application/json"));
        assertFalse(CronetHttp.isDownloadableType("application/xhtml+xml"));
        assertFalse(CronetHttp.isDownloadableType("application/xml"));
    }

    @Test
    public void acceptsAudioAndUnknownTypes() {
        assertTrue(CronetHttp.isDownloadableType("audio/mp4"));
        assertTrue(CronetHttp.isDownloadableType("audio/mpeg"));
        assertTrue(CronetHttp.isDownloadableType("application/octet-stream"));
        assertTrue(CronetHttp.isDownloadableType("binary/octet-stream"));
        assertTrue("服务端未给类型时不预判", CronetHttp.isDownloadableType(null));
        assertTrue(CronetHttp.isDownloadableType(""));
    }
}
