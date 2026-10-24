package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 应用内更新的纯逻辑契约：版本比较与 releases/latest 解析。
 *
 * <p>更新入口一旦判断错方向，用户要么永远收不到更新，要么被反复提示同一个版本，
 * 因此这两块抽成不依赖网络与 Android 框架的纯函数单独验证。
 */
public class UpdateCheckerTest {

    @Test
    public void comparesDottedVersionsNumerically() {
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.0") > 0);
        assertTrue(UpdateChecker.compareVersions("1.0.0", "1.1.0") < 0);
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "1.0.0"));
        // 不能按字符串比较：1.10 比 1.9 新
        assertTrue(UpdateChecker.compareVersions("1.10.0", "1.9.9") > 0);
        // 段数不同时，缺省段按 0 处理
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"));
        assertTrue(UpdateChecker.compareVersions("1.1.1", "1.1") > 0);
    }

    @Test
    public void ignoresTagPrefix() {
        assertEquals("1.1.0", UpdateChecker.stripPrefix("v1.1.0"));
        assertEquals("1.1.0", UpdateChecker.stripPrefix("V1.1.0"));
        assertEquals("1.1.0", UpdateChecker.stripPrefix("1.1.0"));
        assertTrue(UpdateChecker.compareVersions("v1.1.0", "1.0.0") > 0);
    }

    @Test
    public void parsesLatestReleaseWithApkAsset() throws Exception {
        String json = "{"
                + "\"tag_name\":\"v1.1.0\","
                + "\"body\":\"修复若干问题\","
                + "\"html_url\":\"https://github.com/rhoele/Tune-Wikite-Android/releases/tag/v1.1.0\","
                + "\"assets\":["
                + "{\"name\":\"SHA256SUMS.txt\",\"browser_download_url\":\"https://example.com/SUMS\",\"size\":90},"
                + "{\"name\":\"TuneWikite-v1.1.0-release.apk\","
                + "\"browser_download_url\":\"https://example.com/TuneWikite-v1.1.0-release.apk\","
                + "\"size\":6890000}"
                + "]"
                + "}";

        UpdateChecker.Release release = UpdateChecker.parse(json);

        assertEquals("1.1.0", release.version);
        assertEquals("v1.1.0", release.tag);
        assertEquals("修复若干问题", release.notes);
        assertEquals("https://example.com/TuneWikite-v1.1.0-release.apk", release.apkUrl);
        assertEquals(6890000L, release.apkSize);
    }

    @Test
    public void releaseWithoutApkAssetIsReportedAsMissing() throws Exception {
        String json = "{"
                + "\"tag_name\":\"v1.2.0\","
                + "\"body\":\"\","
                + "\"html_url\":\"https://example.com\","
                + "\"assets\":[{\"name\":\"notes.txt\","
                + "\"browser_download_url\":\"https://example.com/notes.txt\",\"size\":1}]"
                + "}";

        UpdateChecker.Release release = UpdateChecker.parse(json);

        assertEquals("1.2.0", release.version);
        assertNull(release.apkUrl);
    }

    @Test
    public void notesAreRenderedAsPlainText() {
        String raw = "<!--\n  发布前必读：不要外传\n-->\n"
                + "# Tune Wikite v1.1.0\n\n"
                + "## 本次更新\n\n"
                + "- **修复** 若干问题\n\n"
                + "| 文件 | 说明 |\n|---|---|\n| a.apk | 安装包 |\n\n"
                + "```powershell\nGet-FileHash .\\a.apk\n```";

        String plain = UpdateChecker.plainNotes(raw);

        assertFalse(plain.contains("<!--"));
        assertFalse(plain.contains("##"));
        assertFalse(plain.contains("**"));
        assertFalse(plain.contains("|---"));
        assertFalse(plain.contains("```"));
        assertFalse(plain.contains("`"));
        assertTrue(plain.startsWith("Tune Wikite v1.1.0"));
        assertTrue(plain.contains("修复 若干问题"));
        assertTrue(plain.contains("a.apk · 安装包"));
    }
}
