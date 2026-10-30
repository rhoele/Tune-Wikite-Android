package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 封面加载链路的单测（本轮：资源加载性能优化）。
 *
 * <p>固化的契约：
 * <ul>
 *   <li>域名白名单：仅网易云 CDN 与聚合接口自有域名可加载，其余一律拒绝；</li>
 *   <li>CDN 尺寸参数：网易云 CDN 地址会被附加 {@code ?param=WyH}，
 *       其余域名与已带查询串的地址保持原样。</li>
 * </ul>
 */
public class CoverLoaderTest {

    // ---------------------- 域名白名单 ----------------------

    @Test
    public void allowsNeteaseCdn() {
        assertTrue(CoverLoader.isAllowed("https://p1.music.126.net/abc.jpg"));
        assertTrue(CoverLoader.isAllowed("https://music.126.net/abc.jpg"));
        assertTrue(CoverLoader.isAllowed("https://p2.music.126.net/abc.jpg"));
    }

    @Test
    public void allowsAggregationHost() {
        assertTrue(CoverLoader.isAllowed("https://ne.cdgz.top/cover/abc.png"));
    }

    @Test
    public void rejectsForeignOrMalformedUrl() {
        assertFalse(CoverLoader.isAllowed(null));
        assertFalse(CoverLoader.isAllowed(""));
        assertFalse(CoverLoader.isAllowed("https://example.com/a.jpg"));
        // 缺少协议头
        assertFalse(CoverLoader.isAllowed("p1.music.126.net/a.jpg"));
        // 大小写不敏感，但协议必须是 https
        assertFalse(CoverLoader.isAllowed("ftp://p1.music.126.net/a.jpg"));
        assertFalse(CoverLoader.isAllowed("http://p2.music.126.net/abc.jpg"));
    }

    /** 白名单必须按 host 判定：路径 / 查询串 / 伪后缀里出现白名单域名都不算 */
    @Test
    public void rejectsSpoofedHost() {
        assertFalse(CoverLoader.isAllowed("https://evil.example/music.126.net/a.jpg"));
        assertFalse(CoverLoader.isAllowed("https://music.126.net.evil.example/a.jpg"));
        assertFalse(CoverLoader.isAllowed("https://evil.example/?u=https://p1.music.126.net/a.jpg"));
        assertFalse(CoverLoader.isAllowed("https://xne.cdgz.top/a.jpg"));
        assertFalse(CoverLoader.isAllowed("https://ne.cdgz.top.evil.example/a.jpg"));
    }

    /** 磁盘缓存键必须区分目标边长，避免小图挡住之后的高清请求 */
    @Test
    public void diskKeySeparatesTargetSize() {
        String url = "https://p1.music.126.net/abc.jpg";
        assertFalse(CoverLoader.diskKey(url, 200).equals(CoverLoader.diskKey(url, 600)));
        assertEquals(CoverLoader.diskKey(url, 200), CoverLoader.diskKey(url, 200));
    }

    // ---------------------- CDN 尺寸参数 ----------------------

    @Test
    public void appendsSizeParamForNeteaseCdn() {
        assertEquals("https://p1.music.126.net/abc.jpg?param=200y200",
                CoverLoader.withCdnSize("https://p1.music.126.net/abc.jpg", 200));
    }

    @Test
    public void keepsUrlWithoutQueryWhenTargetUnknown() {
        String url = "https://p1.music.126.net/abc.jpg";
        assertEquals(url, CoverLoader.withCdnSize(url, 0));
        assertEquals(url, CoverLoader.withCdnSize(url, -1));
    }

    /** 已带查询串的地址可能携带防盗链签名，绝不能改写 */
    @Test
    public void keepsSignedUrlUntouched() {
        String signed = "https://p1.music.126.net/abc.jpg?authKey=deadbeef";
        assertEquals(signed, CoverLoader.withCdnSize(signed, 200));
    }

    /** 非网易云 CDN 域名不追加参数，避免被第三方服务当成非法请求 */
    @Test
    public void skipsNonCdnHost() {
        String url = "https://ne.cdgz.top/cover/abc.png";
        assertEquals(url, CoverLoader.withCdnSize(url, 200));
    }

    // ---------------------- 采样率 ----------------------

    @Test
    public void samplesLargeImageDownToTarget() {
        // 3000px 原图、目标 200px：允许两倍以内，采样到 8（375px）
        assertEquals(8, CoverLoader.sampleSizeFor(3000, 3000, 200));
        // 长宽不等时按长边计算
        assertEquals(8, CoverLoader.sampleSizeFor(3000, 1200, 200));
    }

    @Test
    public void keepsSmallImageUnsampled() {
        assertEquals(1, CoverLoader.sampleSizeFor(240, 240, 200));
        assertEquals(1, CoverLoader.sampleSizeFor(0, 0, 200));
        // 目标未知时不采样
        assertEquals(1, CoverLoader.sampleSizeFor(3000, 3000, 0));
        assertEquals(1, CoverLoader.sampleSizeFor(3000, 3000, -1));
    }

    /** 超大原图必须给出足够大的采样率，避免解码时撑爆堆内存 */
    @Test
    public void samplesVeryLargeImageAggressively() {
        int sample = CoverLoader.sampleSizeFor(12000, 8000, 200);
        assertEquals(32, sample);
        assertTrue(12000 / sample <= 200 * 2);
    }
}
