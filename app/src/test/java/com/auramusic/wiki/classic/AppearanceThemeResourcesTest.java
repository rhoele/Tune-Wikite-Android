package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 外观主题资源的回归单测。
 *
 * <p>固化的契约（对应第一阶段 BUG）：调色板主题分支必须覆盖<b>全部 32 个 MD3 色彩令牌</b>，
 * 特别是 surface / background 家族。此前的分支只覆盖 primary / secondary 家族 6 个令牌，
 * 导致切换调色板后控件变色而应用背景仍是品牌中性色。
 *
 * <p>另固化：{@link AppearanceManager#PRESET_PALETTES} 中的每套预设都必须在
 * {@code palettes.xml} / {@code palettes-night.xml} 中产出完整色板，新增预设若忘记生成
 * 资源会在本测试立即失败。
 */
public class AppearanceThemeResourcesTest {

    /** MaterialDynamicColors 导出的 32 项令牌，顺序与生成器一致。 */
    private static final List<String> TOKENS = Arrays.asList(
            "primary", "on_primary", "primary_container", "on_primary_container", "inverse_primary",
            "secondary", "on_secondary", "secondary_container", "on_secondary_container",
            "tertiary", "on_tertiary", "tertiary_container", "on_tertiary_container",
            "error", "on_error", "error_container", "on_error_container",
            "background", "on_background", "surface", "on_surface",
            "surface_variant", "on_surface_variant",
            "surface_container_low", "surface_container", "surface_container_high",
            "surface_container_highest", "inverse_surface", "inverse_on_surface",
            "outline", "outline_variant");

    /** 必须被每套调色板分支覆盖、且直接决定「背景是否跟着变色」的关键令牌。 */
    private static final List<String> SURFACE_TOKENS = Arrays.asList(
            "background", "on_background", "surface", "on_surface",
            "surface_container_low", "surface_container", "surface_container_high",
            "surface_container_highest", "surface_variant", "on_surface_variant");

    private static List<String> presets() {
        return Arrays.asList(AppearanceManager.PRESET_PALETTES);
    }

    private static String styleName(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    @Test
    public void presetListCoversAllGeneratedPalettes() {
        assertEquals(Arrays.asList("red", "blue", "green", "purple", "amber",
                        "teal", "coral", "rose", "graphite"),
                presets());
    }

    @Test
    public void lightPalettesDefineEveryTokenForEveryPreset() throws Exception {
        assertFullPalette("values/palettes.xml");
    }

    @Test
    public void darkPalettesDefineEveryTokenForEveryPreset() throws Exception {
        assertFullPalette("values-night/palettes.xml");
    }

    @Test
    public void everyPresetOverridesSurfaceAndBackgroundFamily() throws Exception {
        String xml = read("values/themes_palettes.xml");
        for (String preset : presets()) {
            if (AppearanceManager.PALETTE_RED.equals(preset)) {
                // 品牌红即基础主题，surface 家族由 Theme.ClassicWiki 的 md_* 令牌承担
                continue;
            }
            for (String token : SURFACE_TOKENS) {
                assertContains(xml, "@color/palette_" + preset + "_" + token,
                        "主题分支 " + styleName(preset) + " 缺少 " + token + " 覆盖");
            }
            assertContains(xml, "android:colorBackground\">@color/palette_" + preset + "_background",
                    styleName(preset) + " 未覆盖 android:colorBackground");
            assertContains(xml, "android:windowBackground\">@color/palette_" + preset + "_background",
                    styleName(preset) + " 未覆盖 android:windowBackground");
            assertContains(xml, "android:statusBarColor\">@color/palette_" + preset + "_surface",
                    styleName(preset) + " 未覆盖 android:statusBarColor");
            assertContains(xml, "android:navigationBarColor\">@color/palette_" + preset + "_surface",
                    styleName(preset) + " 未覆盖 android:navigationBarColor");
        }
    }

    @Test
    public void everyPresetStyleInheritsBaseTheme() throws Exception {
        String xml = read("values/themes_palettes.xml");
        for (String preset : presets()) {
            if (AppearanceManager.PALETTE_RED.equals(preset)) {
                continue;
            }
            assertContains(xml, "<style name=\"Theme.ClassicWiki." + styleName(preset)
                    + "\" parent=\"Theme.ClassicWiki\">", styleName(preset) + " 未继承基础主题");
        }
    }

    private void assertFullPalette(String relativePath) throws Exception {
        String xml = read(relativePath);
        for (String preset : presets()) {
            if (AppearanceManager.PALETTE_RED.equals(preset)) {
                continue;
            }
            for (String token : TOKENS) {
                assertContains(xml, "name=\"palette_" + preset + "_" + token + "\"",
                        relativePath + " 缺少 palette_" + preset + "_" + token);
            }
        }
    }

    private static void assertContains(String haystack, String needle, String message) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(message + "（未找到：" + needle + "）");
        }
    }

    private static String read(String relativePath) throws Exception {
        File file = locate("app/src/main/res/" + relativePath);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 单测工作目录可能是模块目录也可能是工程根目录，逐级向上查找资源文件。 */
    private static File locate(String relativePath) {
        File dir = new File(".").getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relativePath);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到资源文件：" + relativePath);
    }
}
