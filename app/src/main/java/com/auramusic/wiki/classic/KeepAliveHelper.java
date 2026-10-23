package com.auramusic.wiki.classic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 保活相关的系统设置入口。
 *
 * <p>普通应用<b>无法程序化</b>把自己加入厂商的自启动白名单——这些开关位于各 ROM 的私有
 * 组件中，没有公开 API。因此这里只做两件事：
 * <ol>
 *   <li>申请 Android 标准的电池优化豁免（Doze 白名单）；</li>
 *   <li>按当前厂商逐个尝试拉起对应的「自启动 / 后台运行」管理页，找不到则退回应用详情页。</li>
 * </ol>
 */
final class KeepAliveHelper {

    private KeepAliveHelper() {
    }

    /** 是否已加入系统电池优化白名单。 */
    static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** 申请电池优化豁免；已被系统拒绝或无可跳转页面时退回应用详情。 */
    static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || isBatteryOptimizationIgnored(activity)) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    /**
     * 尝试打开厂商自启动管理页，成功返回 true。
     *
     * <p>候选 Intent 先经 {@link PackageManager#resolveActivity} 过滤：只接受
     * 「已导出且不要求调用方持有权限」的组件。这样既能跳过被 ROM 设为签名级的页面
     * （例如 ColorOS 16 的自启动列表要求 {@code oplus.permission.OPLUS_COMPONENT_SAFE}），
     * 也能避免盲拉起把用户带到无关页面。Android 11+ 需要在清单里用
     * {@code <queries>} 声明厂商包名，否则解析结果为 null。
     */
    static boolean openAutoStartSettings(Activity activity) {
        for (Intent intent : autoStartIntents()) {
            Intent ready = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!isLaunchable(activity, ready)) {
                continue;
            }
            try {
                activity.startActivity(ready);
                return true;
            } catch (Exception ignored) {
                // 解析通过但启动仍失败（个别 ROM 做了运行时校验），继续尝试下一个
            }
        }
        return false;
    }

    private static boolean isLaunchable(Context context, Intent intent) {
        try {
            ResolveInfo info = context.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo activityInfo = info == null ? null : info.activityInfo;
            return activityInfo != null && activityInfo.exported && activityInfo.permission == null;
        } catch (Exception e) {
            return false;
        }
    }

    static void openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    /**
     * 当前系统的展示名（含版本号），用于设置页说明与兜底提示。
     *
     * <p>不能只看 {@link Build#BRAND}：一加国内版与海外版分属 ColorOS / OxygenOS，
     * 同一品牌下系统完全不同。这里直接读 ROM 自己暴露的版本属性：
     * <ul>
     *   <li>ColorOS / OxygenOS / realme UI：{@code ro.build.version.oplusrom}
     *       （一加按 {@code ro.vendor.oplus.regionmark} 区分国内 ColorOS 与海外 OxygenOS）；</li>
     *   <li>旧版 OxygenOS：{@code ro.oxygen.version}；旧版 ColorOS：{@code ro.build.version.opporom}；</li>
     *   <li>MIUI / HyperOS：{@code ro.miui.ui.version.name}；</li>
     *   <li>HarmonyOS / EMUI：{@code ro.build.version.harmony} / {@code ro.build.version.emui}；</li>
     *   <li>vivo：{@code ro.vivo.os.name} + {@code ro.vivo.os.version}。</li>
     * </ul>
     */
    static String osName() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();

        String oxygen = readProp("ro.oxygen.version");
        if (!blank(oxygen)) {
            return withVersion("OxygenOS", oxygen);
        }
        String oplus = readProp("ro.build.version.oplusrom");
        if (!blank(oplus)) {
            if (brand.contains("oneplus")) {
                return withVersion(isChinaRegion() ? "ColorOS" : "OxygenOS", oplus);
            }
            if (brand.contains("realme")) {
                return withVersion("realme UI", oplus);
            }
            return withVersion("ColorOS", oplus);
        }
        String colorOs = readProp("ro.build.version.opporom");
        if (!blank(colorOs)) {
            return withVersion("ColorOS", colorOs);
        }
        String realme = readProp("ro.build.version.realmeui");
        if (!blank(realme)) {
            return withVersion("realme UI", realme);
        }
        String miui = readProp("ro.miui.ui.version.name");
        if (!blank(miui)) {
            return "MIUI " + miui;
        }
        String harmony = readProp("ro.build.version.harmony");
        if (!blank(harmony)) {
            return withVersion("HarmonyOS", harmony);
        }
        String emui = readProp("ro.build.version.emui");
        if (!blank(emui)) {
            return "EMUI " + emui.replace("EmotionUI_", "");
        }
        String vivoName = readProp("ro.vivo.os.name");
        String vivoVersion = readProp("ro.vivo.os.version");
        if (!blank(vivoName) || !blank(vivoVersion)) {
            String name = blank(vivoName) ? "OriginOS" : vivoName;
            return withVersion(name, vivoVersion);
        }

        // 属性缺失时退回品牌名，至少不会显示成别的系统
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            return "MIUI / HyperOS";
        }
        if (brand.contains("huawei") || brand.contains("honor")) {
            return "HarmonyOS / EMUI";
        }
        if (brand.contains("oneplus")) {
            return "OxygenOS / ColorOS";
        }
        if (brand.contains("oppo") || brand.contains("realme")) {
            return "ColorOS";
        }
        if (brand.contains("vivo") || brand.contains("iqoo")) {
            return "OriginOS";
        }
        if (brand.contains("meizu")) {
            return "Flyme";
        }
        if (brand.contains("samsung")) {
            return "One UI";
        }
        return Build.BRAND == null ? "当前系统" : Build.BRAND;
    }

    /** 一加国内版用 ColorOS、海外版用 OxygenOS，以此属性区分。 */
    private static boolean isChinaRegion() {
        String region = readProp("ro.vendor.oplus.regionmark");
        if (blank(region)) {
            region = readProp("persist.sys.oplus.region");
        }
        return "CN".equalsIgnoreCase(region.trim());
    }

    /** 只取版本号的主版本（V17.0.0 → 17），展示更干净。 */
    private static String withVersion(String name, String rawVersion) {
        if (blank(rawVersion)) {
            return name;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)").matcher(rawVersion);
        String version = matcher.find() ? matcher.group(1) : rawVersion.trim();
        return name + " " + version;
    }

    /** 读取系统属性；非 SDK 接口，失败时返回空串。 */
    private static String readProp(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Object value = cls.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 厂商自启动入口清单。
     *
     * <p>先按当前品牌把对应的入口排到最前，再追加其它品牌作为兜底（同一 ROM 家族
     * 常有多个包名并存，例如 ColorOS 的新旧组件）。列表里的组件不存在时
     * {@code startActivity} 会抛异常，由调用方逐个跳过。
     */
    private static Intent[] autoStartIntents() {
        Intent[] colorOs = {
                // ColorOS 12+ / OxygenOS 12+（自启动列表页在「电池」应用内）
                component("com.oplus.battery",
                        "com.oplus.startupapp.view.StartupAppListActivity"),
                action("com.oplus.battery.permission.startup.StartupAppListActivity"),
                // ColorOS 11 及更早
                component("com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oplus.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter",
                        "com.coloros.privacypermissionsentry.PermissionTopActivity"),
                component("com.oplus.safecenter",
                        "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"),
                // 一加（OxygenOS 早期版本仍保留独立的安全中心）
                component("com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        };
        Intent[] miui = {
                component("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartDetailActivity"),
                action("miui.intent.action.OP_AUTO_START"),
        };
        Intent[] huawei = {
                component("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                component("com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        };
        Intent[] vivo = {
                component("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                component("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        };
        Intent[] meizu = {
                component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
                component("com.meizu.safe", "com.meizu.safe.SecurityCenterActivity"),
        };
        Intent[] samsung = {
                component("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"),
        };
        Intent[] others = {
                // 华硕
                component("com.asus.mobilemanager",
                        "com.asus.mobilemanager.autostart.AutoStartActivity"),
                component("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"),
                // 乐视
                component("com.letv.android.letvsafe",
                        "com.letv.android.letvsafe.AutobootManageActivity"),
                // 联发科
                component("com.mediatek.duraspeed",
                        "com.mediatek.duraspeed.view.RunningBoosterMainActivity"),
                // 传音（Tecno / Infinix / itel）
                component("com.transsion.phonemaster",
                        "com.cyin.himgr.autostart.AutoStartActivity"),
                // HTC
                component("com.htc.pitroad",
                        "com.htc.pitroad.landingpage.activity.LandingPageActivity"),
        };

        List<Intent> ordered = new ArrayList<>();
        if (brandMatches("xiaomi", "redmi", "poco")) {
            addAll(ordered, miui);
        } else if (brandMatches("huawei", "honor")) {
            addAll(ordered, huawei);
        } else if (brandMatches("oppo", "oneplus", "realme")) {
            addAll(ordered, colorOs);
        } else if (brandMatches("vivo", "iqoo")) {
            addAll(ordered, vivo);
        } else if (brandMatches("meizu")) {
            addAll(ordered, meizu);
        } else if (brandMatches("samsung")) {
            addAll(ordered, samsung);
        } else if (brandMatches("asus", "tecno", "infinix", "itel", "letv", "htc", "mediatek")) {
            addAll(ordered, others);
        }
        addAll(ordered, colorOs);
        addAll(ordered, miui);
        addAll(ordered, huawei);
        addAll(ordered, vivo);
        addAll(ordered, meizu);
        addAll(ordered, samsung);
        addAll(ordered, others);
        return ordered.toArray(new Intent[0]);
    }

    private static boolean brandMatches(String... keys) {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        for (String key : keys) {
            if (brand.contains(key) || manufacturer.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static void addAll(List<Intent> target, Intent[] intents) {
        Collections.addAll(target, intents);
    }

    private static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    private static Intent action(String action) {
        return new Intent(action);
    }
}
