package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 外观设置管理（需求 4：MD3 外观模块）。
 *
 * 三类可配置项，全部即时生效并持久化：
 *   1. 主题模式（跟随系统 / 浅色 / 深色）—— 交给 {@link AppCompatDelegate}，
 *      切换时 AppCompat 会自动重建活动栈，无需手动干预；
 *   2. 调色板（跟随系统壁纸 / 网易红 / 靛蓝 / 青竹 / 紫罗兰 / 琥珀）——
 *      映射到 {色}Theme.ClassicWiki.{色} 主题分支，由 {@link BaseActivity} 在
 *      onCreate 中 setTheme 应用；Android 12+ 选「跟随系统壁纸」时改由动态取色接管；
 *   3. 字体大小（小 / 标准 / 大 / 超大）—— 通过 {@link #wrap(Context)} 覆写
 *      Configuration.fontScale，在 attachBaseContext 阶段生效，全部 sp 字号统一缩放。
 *
 * 调色板与字体的变更会推进 {@link #KEY_REVISION}，{@link BaseActivity#onResume} 据此
 * 对已存在于返回栈中的页面执行一次 recreate，保证「实时生效」覆盖整个应用而非仅当前页。
 */
public final class AppearanceManager {

    public static final String KEY_THEME_MODE = "appearance_theme_mode";
    public static final String KEY_PALETTE = "appearance_palette";
    public static final String KEY_FONT_SCALE = "appearance_font_scale";
    public static final String KEY_REVISION = "appearance_revision";

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static final String PALETTE_DYNAMIC = "dynamic";
    public static final String PALETTE_RED = "red";
    public static final String PALETTE_BLUE = "blue";
    public static final String PALETTE_GREEN = "green";
    public static final String PALETTE_PURPLE = "purple";
    public static final String PALETTE_AMBER = "amber";
    public static final String PALETTE_TEAL = "teal";
    public static final String PALETTE_CORAL = "coral";
    public static final String PALETTE_ROSE = "rose";
    public static final String PALETTE_GRAPHITE = "graphite";

    /**
     * 预制调色板的有序清单（不含「跟随系统壁纸」）。
     * 设置页按此顺序数据驱动渲染，新增预设只需在此登记并补一套主题分支即可扩展。
     */
    public static final String[] PRESET_PALETTES = {
            PALETTE_RED, PALETTE_BLUE, PALETTE_GREEN, PALETTE_PURPLE,
            PALETTE_AMBER, PALETTE_TEAL, PALETTE_CORAL, PALETTE_ROSE, PALETTE_GRAPHITE
    };

    public static final float FONT_SMALL = 0.85f;
    public static final float FONT_DEFAULT = 1.0f;
    public static final float FONT_LARGE = 1.15f;
    public static final float FONT_XLARGE = 1.30f;

    // ---------------------- 交互与动效（第二阶段） ----------------------

    /** 底部导航栏：悬浮胶囊（MD3 推荐） */
    public static final String NAV_FLOATING = "floating";
    /** 底部导航栏：贴底通栏（原样式） */
    public static final String NAV_ATTACHED = "attached";

    // ---------------------- 底栏玻璃效果（亚克力 / 液态玻璃） ----------------------

    /** 底栏玻璃：关闭（使用不透明表面色） */
    public static final int GLASS_NONE = 0;
    /** 底栏玻璃：亚克力（中等模糊 + 较高不透明度） */
    public static final int GLASS_ACRYLIC = 1;
    /** 底栏玻璃：液态玻璃（更强模糊 + 高通透 + 高光描边） */
    public static final int GLASS_LIQUID = 2;

    /** 振动强度：轻 */
    public static final int HAPTIC_LIGHT = 0;
    /** 振动强度：标准 */
    public static final int HAPTIC_STANDARD = 1;
    /** 振动强度：强 */
    public static final int HAPTIC_STRONG = 2;

    /** 动效强度：关闭（不播放任何动画） */
    public static final float MOTION_OFF = 0f;
    /** 动效强度：标准（系统原生时长） */
    public static final float MOTION_STANDARD = 1.0f;
    /** 动效强度：强调（时长略放长，位移略放大） */
    public static final float MOTION_EMPHASIZED = 1.4f;

    private AppearanceManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return WikiPrefs.get(context);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public static int themeMode(Context context) {
        return prefs(context).getInt(KEY_THEME_MODE, THEME_SYSTEM);
    }

    public static String palette(Context context) {
        String v = prefs(context).getString(KEY_PALETTE, PALETTE_RED);
        if (v == null || v.isEmpty()) {
            return PALETTE_RED;
        }
        // 兼容旧版本把方案编码进调色板键（seed|variant）的数据
        int split = v.indexOf('|');
        return split < 0 ? v : v.substring(0, split);
    }

    /**
     * 当前色彩方案。与种子色分开存储，因此「跟随系统壁纸」也能保留用户选的方案，
     * 之后切回静态种子色时不会退回标准方案。
     */
    public static String variant(Context context) {
        String v = prefs(context).getString(WikiPrefs.KEY_SCHEME_VARIANT, null);
        if (v == null) {
            // 旧数据迁移：从旧的 seed|variant 键里取一次
            String legacy = prefs(context).getString(KEY_PALETTE, "");
            int split = legacy == null ? -1 : legacy.indexOf('|');
            v = split < 0 ? VARIANT_TONAL : legacy.substring(split + 1);
        }
        return v;
    }

    public static void setVariant(Context context, String variant) {
        prefs(context).edit()
                .putString(WikiPrefs.KEY_SCHEME_VARIANT, variant)
                .putInt(KEY_REVISION, revision(context) + 1)
                .commit();
    }

    public static float fontScale(Context context) {
        float v = prefs(context).getFloat(KEY_FONT_SCALE, FONT_DEFAULT);
        return v <= 0f ? FONT_DEFAULT : v;
    }

    /** 外观修订号：调色板 / 字体变更时自增，用于驱动已存在页面的重建。 */
    public static int revision(Context context) {
        return prefs(context).getInt(KEY_REVISION, 0);
    }

    public static boolean isDynamicPalette(Context context) {
        return PALETTE_DYNAMIC.equals(palette(context));
    }

    /** 动态取色仅在 Android 12（API 31）及以上可用。 */
    public static boolean dynamicColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** 当前外观对应的主题资源（动态调色板与品牌红共用基础主题）。 */
    public static int themeRes(Context context) {
        return themeResFor(palette(context), variant(context));
    }

    // ---------------------- 色彩方案变体 ----------------------

    /** 标准（TonalSpot，与旧版预制主题一致） */
    public static final String VARIANT_TONAL = "tonal";
    /** 鲜明（高彩度） */
    public static final String VARIANT_VIBRANT = "vibrant";
    /** 表现（大幅色相旋转） */
    public static final String VARIANT_EXPRESSIVE = "expressive";
    /** 中性（低彩度） */
    public static final String VARIANT_NEUTRAL = "neutral";

    public static final String[] VARIANTS = {
            VARIANT_TONAL, VARIANT_VIBRANT, VARIANT_EXPRESSIVE, VARIANT_NEUTRAL
    };

    /** 把「种子色 + 方案」组合成存储键；标准方案保持旧的纯种子色键。 */
    public static String combinePalette(String seed, String variant) {
        return VARIANT_TONAL.equals(variant) || variant == null ? seed : seed + "|" + variant;
    }

    public static String paletteSeed(String palette) {
        if (palette == null) {
            return PALETTE_RED;
        }
        int split = palette.indexOf('|');
        return split < 0 ? palette : palette.substring(0, split);
    }

    public static String paletteSeed(Context context) {
        return paletteSeed(palette(context));
    }

    public static String paletteVariant(String palette) {
        if (palette == null) {
            return VARIANT_TONAL;
        }
        int split = palette.indexOf('|');
        return split < 0 ? VARIANT_TONAL : palette.substring(split + 1);
    }

    public static String paletteVariant(Context context) {
        return variant(context);
    }

    public static int variantLabelRes(String variant) {
        switch (variant) {
            case VARIANT_VIBRANT:
                return R.string.appearance_variant_vibrant;
            case VARIANT_EXPRESSIVE:
                return R.string.appearance_variant_expressive;
            case VARIANT_NEUTRAL:
                return R.string.appearance_variant_neutral;
            default:
                return R.string.appearance_variant_tonal;
        }
    }

    /** 调色板 key 到主题资源的映射，供设置页预览色块与主题应用共用。 */
    public static int themeResFor(String palette) {
        return themeResFor(paletteSeed(palette), paletteVariant(palette));
    }

    /** 种子色 + 方案的组合键。 */
    public static String paletteKey(String seed, String variant) {
        return seed + "|" + variant;
    }

    public static int themeResFor(String seed, String variant) {
        // 动态取色由系统提供唯一一套令牌，方案不参与取色
        if (PALETTE_DYNAMIC.equals(seed)) {
            return R.style.Theme_ClassicWiki;
        }
        // 非标准方案：交给由 Material HCT 生成的 27 套主题分支
        if (!VARIANT_TONAL.equals(variant)) {
            int generated = GeneratedPalettes.styleFor(paletteKey(seed, variant));
            if (generated != 0) {
                return generated;
            }
        }
        switch (seed) {
            case PALETTE_BLUE:
                return R.style.Theme_ClassicWiki_Blue;
            case PALETTE_GREEN:
                return R.style.Theme_ClassicWiki_Green;
            case PALETTE_PURPLE:
                return R.style.Theme_ClassicWiki_Purple;
            case PALETTE_AMBER:
                return R.style.Theme_ClassicWiki_Amber;
            case PALETTE_TEAL:
                return R.style.Theme_ClassicWiki_Teal;
            case PALETTE_CORAL:
                return R.style.Theme_ClassicWiki_Coral;
            case PALETTE_ROSE:
                return R.style.Theme_ClassicWiki_Rose;
            case PALETTE_GRAPHITE:
                return R.style.Theme_ClassicWiki_Graphite;
            case PALETTE_RED:
            case PALETTE_DYNAMIC:
            default:
                return R.style.Theme_ClassicWiki;
        }
    }

    /** 调色板主色的颜色资源，供设置页色块预览（随深浅色自动取对应值）。 */
    public static int palettePrimaryColorRes(String palette) {
        int generated = GeneratedPalettes.primaryColorRes(palette);
        if (generated != 0) {
            return generated;
        }
        switch (paletteSeed(palette)) {
            case PALETTE_BLUE:
                return R.color.palette_blue_primary;
            case PALETTE_GREEN:
                return R.color.palette_green_primary;
            case PALETTE_PURPLE:
                return R.color.palette_purple_primary;
            case PALETTE_AMBER:
                return R.color.palette_amber_primary;
            case PALETTE_TEAL:
                return R.color.palette_teal_primary;
            case PALETTE_CORAL:
                return R.color.palette_coral_primary;
            case PALETTE_ROSE:
                return R.color.palette_rose_primary;
            case PALETTE_GRAPHITE:
                return R.color.palette_graphite_primary;
            case PALETTE_RED:
            case PALETTE_DYNAMIC:
            default:
                return R.color.md_primary;
        }
    }

    /** 调色板主色之上的前景色资源（选中勾选用色）。 */
    public static int paletteOnPrimaryColorRes(String palette) {
        int generated = GeneratedPalettes.onPrimaryColorRes(palette);
        if (generated != 0) {
            return generated;
        }
        switch (paletteSeed(palette)) {
            case PALETTE_BLUE:
                return R.color.palette_blue_on_primary;
            case PALETTE_GREEN:
                return R.color.palette_green_on_primary;
            case PALETTE_PURPLE:
                return R.color.palette_purple_on_primary;
            case PALETTE_AMBER:
                return R.color.palette_amber_on_primary;
            case PALETTE_TEAL:
                return R.color.palette_teal_on_primary;
            case PALETTE_CORAL:
                return R.color.palette_coral_on_primary;
            case PALETTE_ROSE:
                return R.color.palette_rose_on_primary;
            case PALETTE_GRAPHITE:
                return R.color.palette_graphite_on_primary;
            case PALETTE_RED:
            case PALETTE_DYNAMIC:
            default:
                return R.color.md_on_primary;
        }
    }

    // ------------------------------------------------------------------
    // 写（均同步提交，避免切换后立即读回旧值）
    // ------------------------------------------------------------------

    public static void applyDefaultNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(themeMode(context)));
    }

    public static void setThemeMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).commit();
        applyDefaultNightMode(context);
    }

    public static void setPalette(Context context, String palette) {
        String seed = paletteSeed(palette);
        android.content.SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_PALETTE, seed)
                .putInt(KEY_REVISION, revision(context) + 1);
        // 允许「seed|variant」形式的组合键一次性写入两者
        int split = palette == null ? -1 : palette.indexOf('|');
        if (split >= 0) {
            editor.putString(WikiPrefs.KEY_SCHEME_VARIANT, palette.substring(split + 1));
        }
        editor.commit();
    }

    public static void setFontScale(Context context, float scale) {
        prefs(context).edit()
                .putFloat(KEY_FONT_SCALE, scale)
                .putInt(KEY_REVISION, revision(context) + 1)
                .commit();
    }

    // ------------------------------------------------------------------
    // 交互与动效（第二阶段）
    //
    // 这些选项在播放时即时读取，因此无需推进外观修订号触发重建——
    // 避免用户每改一个开关就整栈 recreate。唯一例外是底部导航栏样式，
    // 由 MainActivity 在 onResume 中幂等重排（见 MainActivity#applyNavStyle）。
    // ------------------------------------------------------------------

    /** 系统振动反馈总开关，默认开启。 */
    public static boolean hapticsEnabled(Context context) {
        return prefs(context).getBoolean(WikiPrefs.KEY_HAPTICS, true);
    }

    public static void setHapticsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(WikiPrefs.KEY_HAPTICS, enabled).apply();
    }

    /** 振动强度档位，非法值回落到标准。 */
    public static int hapticLevel(Context context) {
        int v = prefs(context).getInt(WikiPrefs.KEY_HAPTIC_LEVEL, HAPTIC_STANDARD);
        return v < HAPTIC_LIGHT || v > HAPTIC_STRONG ? HAPTIC_STANDARD : v;
    }

    public static void setHapticLevel(Context context, int level) {
        prefs(context).edit().putInt(WikiPrefs.KEY_HAPTIC_LEVEL, level).apply();
    }

    /** 动效强度，非法值回落到标准。 */
    public static float motionScale(Context context) {
        float v = prefs(context).getFloat(WikiPrefs.KEY_MOTION_SCALE, MOTION_STANDARD);
        return v < MOTION_OFF ? MOTION_STANDARD : v;
    }

    public static void setMotionScale(Context context, float scale) {
        prefs(context).edit().putFloat(WikiPrefs.KEY_MOTION_SCALE, scale).apply();
    }

    public static boolean motionsEnabled(Context context) {
        return motionScale(context) > MOTION_OFF + 0.01f;
    }

    /** 页面层级切换的离场模糊开关，默认开启（仅 Android 12+ 实际生效）。 */
    public static boolean blurEnabled(Context context) {
        return prefs(context).getBoolean(WikiPrefs.KEY_BLUR, true);
    }

    public static void setBlurEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(WikiPrefs.KEY_BLUR, enabled).apply();
    }

    /**
     * 设置行的前导图标是否使用彩色底托（Expressive 风格，默认开启）。
     *
     * <p>关闭后所有行退回中性色底托：形状层级仍在，但不再按分类着色，
     * 适合偏好低饱和界面的用户。
     */
    public static boolean iconTilesEnabled(Context context) {
        // 默认开启：彩色图标底托是 M3 Expressive 设置页的标志性处理
        return prefs(context).getBoolean(WikiPrefs.KEY_ICON_TILES, true);
    }

    public static void setIconTilesEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(WikiPrefs.KEY_ICON_TILES, enabled).apply();
    }

    // ---------------------- 列表密度 ----------------------

    /** 列表密度：紧凑 */
    public static final float DENSITY_COMPACT = 0.88f;
    /** 列表密度：标准 */
    public static final float DENSITY_STANDARD = 1.0f;
    /** 列表密度：宽松 */
    public static final float DENSITY_RELAXED = 1.12f;

    /** 列表行高缩放系数；非法值回落到标准。 */
    public static float densityScale(Context context) {
        float v = prefs(context).getFloat(WikiPrefs.KEY_LIST_DENSITY, DENSITY_STANDARD);
        return v < 0.7f || v > 1.3f ? DENSITY_STANDARD : v;
    }

    public static void setDensityScale(Context context, float scale) {
        prefs(context).edit().putFloat(WikiPrefs.KEY_LIST_DENSITY, scale).apply();
    }

    /** 设置项是否使用「每项一张独立圆角卡」的布局（默认关闭，保持整卡分组）。 */
    public static boolean rowsAsCards(Context context) {
        return "separate".equals(prefs(context).getString(WikiPrefs.KEY_ROW_STYLE, "grouped"));
    }

    public static void setRowsAsCards(Context context, boolean asCards) {
        prefs(context).edit()
                .putString(WikiPrefs.KEY_ROW_STYLE, asCards ? "separate" : "grouped")
                .commit();
    }

    public static int densityLabelRes(float scale) {
        if (scale < DENSITY_STANDARD - 0.01f) {
            return R.string.appearance_density_compact;
        }
        if (scale > DENSITY_STANDARD + 0.01f) {
            return R.string.appearance_density_relaxed;
        }
        return R.string.appearance_density_standard;
    }

    /** 底部导航栏样式，非法值回落到悬浮胶囊。 */
    public static String navStyle(Context context) {
        String v = prefs(context).getString(WikiPrefs.KEY_NAV_STYLE, NAV_FLOATING);
        return NAV_ATTACHED.equals(v) ? NAV_ATTACHED : NAV_FLOATING;
    }

    public static void setNavStyle(Context context, String style) {
        prefs(context).edit().putString(WikiPrefs.KEY_NAV_STYLE, style).apply();
    }

    public static boolean isFloatingNav(Context context) {
        return NAV_FLOATING.equals(navStyle(context));
    }

    /**
     * 底栏玻璃效果档位（默认关闭），非法值回落到关闭。
     *
     * <p>与 {@code BackdropBlurView.STYLE_*} 取值一一对应。
     */
    public static int navGlassStyle(Context context) {
        int v = prefs(context).getInt(WikiPrefs.KEY_NAV_GLASS, GLASS_NONE);
        return v < GLASS_NONE || v > GLASS_LIQUID ? GLASS_NONE : v;
    }

    public static void setNavGlassStyle(Context context, int style) {
        prefs(context).edit().putInt(WikiPrefs.KEY_NAV_GLASS, style).apply();
    }

    /**
     * 是否需要提示「默认外观已调整」。
     *
     * <p>只对老用户提示：本地已经存过外观偏好，说明用的是旧默认值（悬浮底栏 + 亚克力），
     * 新版默认值不会再影响他们，但需要解释一次变化，避免以为设置被重置。
     * 全新安装直接就是新默认值，不需要提示。
     */
    public static boolean needsAppearanceNotice(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(WikiPrefs.KEY_APPEARANCE_NOTICE, false)) {
            return false;
        }
        return p.contains(WikiPrefs.KEY_NAV_GLASS)
                || p.contains(WikiPrefs.KEY_NAV_STYLE)
                || p.contains(WikiPrefs.KEY_BLUR);
    }

    public static void markAppearanceNoticeShown(Context context) {
        prefs(context).edit().putBoolean(WikiPrefs.KEY_APPEARANCE_NOTICE, true).apply();
    }

    public static int navGlassLabelRes(int style) {
        switch (style) {
            case GLASS_NONE:
                return R.string.appearance_nav_glass_none;
            case GLASS_LIQUID:
                return R.string.appearance_nav_glass_liquid;
            default:
                return R.string.appearance_nav_glass_acrylic;
        }
    }

    /** 模糊效果是否在当前设备上可用（RenderEffect 需要 Android 12+）。 */
    public static boolean blurSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static int navStyleLabelRes(String style) {
        return NAV_ATTACHED.equals(style)
                ? R.string.appearance_nav_attached
                : R.string.appearance_nav_floating;
    }

    public static int hapticLevelLabelRes(int level) {
        switch (level) {
            case HAPTIC_LIGHT:
                return R.string.appearance_haptic_light;
            case HAPTIC_STRONG:
                return R.string.appearance_haptic_strong;
            default:
                return R.string.appearance_haptic_standard;
        }
    }

    public static int motionLabelRes(float scale) {
        if (scale <= MOTION_OFF + 0.01f) {
            return R.string.appearance_motion_off;
        }
        if (scale > MOTION_STANDARD + 0.01f) {
            return R.string.appearance_motion_emphasized;
        }
        return R.string.appearance_motion_standard;
    }

    // ------------------------------------------------------------------
    // 字体缩放：在 Activity.attachBaseContext 阶段覆写 Configuration
    // ------------------------------------------------------------------

    public static Context wrap(Context base) {
        float scale = fontScale(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Math.abs(config.fontScale - scale) < 0.01f) {
            return base;
        }
        config.fontScale = scale;
        return base.createConfigurationContext(config);
    }

    // ------------------------------------------------------------------
    // 展示用文案映射（供设置一级目录摘要与二级页选项复用）
    // ------------------------------------------------------------------

    public static int themeLabelRes(int mode) {
        switch (mode) {
            case THEME_LIGHT:
                return R.string.appearance_theme_light;
            case THEME_DARK:
                return R.string.appearance_theme_dark;
            default:
                return R.string.appearance_theme_system;
        }
    }

    public static int paletteLabelRes(String palette) {
        int generated = GeneratedPalettes.labelRes(palette);
        if (generated != 0) {
            return generated;
        }
        switch (paletteSeed(palette)) {
            case PALETTE_DYNAMIC:
                return R.string.appearance_palette_dynamic;
            case PALETTE_BLUE:
                return R.string.appearance_palette_blue;
            case PALETTE_GREEN:
                return R.string.appearance_palette_green;
            case PALETTE_PURPLE:
                return R.string.appearance_palette_purple;
            case PALETTE_AMBER:
                return R.string.appearance_palette_amber;
            case PALETTE_TEAL:
                return R.string.appearance_palette_teal;
            case PALETTE_CORAL:
                return R.string.appearance_palette_coral;
            case PALETTE_ROSE:
                return R.string.appearance_palette_rose;
            case PALETTE_GRAPHITE:
                return R.string.appearance_palette_graphite;
            case PALETTE_RED:
            default:
                return R.string.appearance_palette_red;
        }
    }

    public static int fontLabelRes(float scale) {
        if (scale < FONT_DEFAULT - 0.01f) {
            return R.string.appearance_font_small;
        }
        if (scale > FONT_XLARGE - 0.01f) {
            return R.string.appearance_font_xlarge;
        }
        if (scale > FONT_DEFAULT + 0.01f) {
            return R.string.appearance_font_large;
        }
        return R.string.appearance_font_default;
    }

    private static int nightMode(int mode) {
        switch (mode) {
            case THEME_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case THEME_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }
}
