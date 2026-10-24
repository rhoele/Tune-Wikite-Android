package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 集中管理应用偏好键名，避免多处硬编码字符串导致读写不一致。
 */
public final class WikiPrefs {

    public static final String PREFS_NAME = "wiki_settings";

    public static final String KEY_BG_AUDIT = "bg_audit_enabled";
    public static final String KEY_INTERVAL = "polling_interval_minutes";
    public static final String KEY_COOKIE = "music_cookie";
    /**
     * 后台审核提醒的「已出结果」去重键集合。
     *
     * <p>键格式变更（改用 {@link Contribution#stableKey()}）时须同步改这里的键名，
     * 否则旧键与新键不匹配会把历史已审核条目全部误判为新结果，造成通知轰炸；
     * 换名后 {@code contains()} 为 false，首轮只播种不推送。
     */
    public static final String KEY_DONE_IDS = "audit_known_done_keys_v2";
    /** 接口通道（{@link TransportMode} 的枚举名），与原 APK 的 {@code channel} 偏好一致 */
    public static final String KEY_TRANSPORT = "transport_channel";
    /** 「自动」模式下最近一次成功的通道，用于跳过注定失败的首跳 */
    private static final String KEY_TRANSPORT_HINT = "transport_channel_hint";
    /** eapi 通道所需的设备标识 */
    public static final String KEY_DEVICE_ID = "transport_device_id";
    /** 是否在贡献卡片上展示资源封面（需求 4：部分可读、部分不可读时由用户自主决定） */
    public static final String KEY_SHOW_COVERS = "contrib_show_covers";
    /** 「我的贡献」页是否展示接口通道卡片（需求 3：整块卡片可隐藏，隐藏后下方内容自动上移） */
    public static final String KEY_SHOW_TRANSPORT_CARD = "contrib_show_transport_card";
    /** 「我的贡献」页是否展示账号卡片（含「我的曲库贡献」标题） */
    public static final String KEY_SHOW_ACCOUNT_CARD = "contrib_show_account_card";
    /** 「我的贡献」页是否展示贡献搜索模块（需求 5） */
    public static final String KEY_SHOW_SEARCH = "contrib_show_search";
    /** 贡献搜索卡片布局模式（normal / compact） */
    public static final String KEY_SEARCH_LAYOUT = "contrib_search_layout";
    public static final String SEARCH_LAYOUT_NORMAL = "normal";
    public static final String SEARCH_LAYOUT_COMPACT = "compact";
    /** 发行公司历史输入（JSON 数组，最近使用在前） */
    public static final String KEY_COMPANY_HISTORY = "album_company_history_v1";
    /** 上次启动静默检查更新的时间戳（毫秒），用于限制每天最多一次 */
    public static final String KEY_UPDATE_CHECK_AT = "app_update_check_at";
    /** 用户主动「忽略此版本」的版本号；只有更新的版本才会再次提醒 */
    private static final String KEY_UPDATE_IGNORED = "app_update_ignored_version";

    // ------------------------------------------------------------------
    // 交互与动效（第二阶段：控件体验 / 导航栏 / 转场）
    // ------------------------------------------------------------------

    /** 系统振动反馈总开关 */
    public static final String KEY_HAPTICS = "ui_haptics_enabled";
    /** 振动强度档位（{@link AppearanceManager#HAPTIC_LIGHT} 等） */
    public static final String KEY_HAPTIC_LEVEL = "ui_haptic_level";
    /** 动效强度（0 表示关闭动效，1 标准，>1 强调） */
    public static final String KEY_MOTION_SCALE = "ui_motion_scale";
    /** 底部导航栏样式（floating 悬浮胶囊 / attached 贴底通栏） */
    public static final String KEY_NAV_STYLE = "ui_nav_style";
    /** 页面层级切换时的离场模糊效果开关（Android 12+） */
    public static final String KEY_BLUR = "ui_blur_enabled";
    /** 底栏玻璃效果档位（{@link AppearanceManager#GLASS_NONE} 等） */
    public static final String KEY_NAV_GLASS = "ui_nav_glass";
    /** 设置行前导图标是否使用彩色底托（Expressive 风格） */
    public static final String KEY_ICON_TILES = "ui_icon_tiles";
    /** 列表密度缩放（1.0 标准，<1 紧凑，>1 宽松） */
    public static final String KEY_LIST_DENSITY = "ui_list_density";
    /** 设置项呈现样式：grouped（同一张卡）/ separate（每项独立圆角卡） */
    public static final String KEY_ROW_STYLE = "ui_row_style";
    /** 色彩方案变体（tonal / vibrant / expressive / neutral），与种子色相互独立 */
    public static final String KEY_SCHEME_VARIANT = "appearance_scheme_variant";

    // ------------------------------------------------------------------
    // 推送（FCM）
    // ------------------------------------------------------------------

    /** 最近一次成功上报到自有服务器的 FCM 令牌，避免重复上报 */
    public static final String KEY_FCM_UPLOADED_TOKEN = "fcm_uploaded_token";

    /**
     * 轮询周期下限（分钟）。
     *
     * <p>每轮固定 3 个请求（全部 + 歌词 + 翻译），1 分钟一轮约合每天 4300 次请求，
     * 既有触发风控的风险，也会让射频长期处于唤醒状态；5 分钟是「及时性」与
     * 「请求量 / 耗电」的折中点（约 864 次/天）。
     */
    public static final int MIN_INTERVAL_MINUTES = 5;
    /** 轮询周期上限（分钟），与设置页 Slider 的右端一致 */
    public static final int MAX_INTERVAL_MINUTES = 120;
    /** 设置页 Slider 的步长（分钟） */
    public static final int INTERVAL_STEP_MINUTES = 5;
    public static final int DEFAULT_INTERVAL_MINUTES = 15;

    private WikiPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String cookie(Context context) {
        String v = get(context).getString(KEY_COOKIE, "");
        return v == null ? "" : v.trim();
    }

    /** 上次静默检查更新的时间；0 表示从未检查。 */
    public static long updateCheckedAt(Context context) {
        return get(context).getLong(KEY_UPDATE_CHECK_AT, 0L);
    }

    public static void setUpdateCheckedAt(Context context, long at) {
        get(context).edit().putLong(KEY_UPDATE_CHECK_AT, at).apply();
    }

    /** 被忽略的版本号；空串表示没有忽略任何版本。 */
    public static String ignoredUpdateVersion(Context context) {
        String v = get(context).getString(KEY_UPDATE_IGNORED, "");
        return v == null ? "" : v;
    }

    public static void setIgnoredUpdateVersion(Context context, String version) {
        get(context).edit().putString(KEY_UPDATE_IGNORED, version == null ? "" : version).apply();
    }

    public static boolean hasCookie(Context context) {
        return !cookie(context).isEmpty();
    }

    public static boolean backgroundAuditEnabled(Context context) {
        return get(context).getBoolean(KEY_BG_AUDIT, true);
    }

    public static int pollingIntervalMinutes(Context context) {
        int v = get(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES);
        // 上限收敛到设置页 Slider 的右端：旧版本允许 240–720 分钟，
        // 对这些历史配置按 120 分钟执行（否则界面显示与真实周期不一致）。
        return Math.min(MAX_INTERVAL_MINUTES, Math.max(MIN_INTERVAL_MINUTES, v));
    }

    /** 当前接口通道；未设置时与原 APK 一样回落到「自动」。 */
    public static TransportMode transportMode(Context context) {
        return TransportMode.fromName(get(context).getString(KEY_TRANSPORT, null));
    }

    /**
     * 「自动」模式下最近一次成功的通道。
     *
     * <p>此前 AUTO 每次请求都先打 app 通道，eapi 对网页百科端点稳定返回空响应，
     * 于是每个请求都要多付一次失败往返（实测约 60–110ms）。记住上次成功的通道，
     * 可以把这一跳直接省掉；失败时再回退并更新记忆。
     *
     * @return {@link TransportMode#EAPI} / {@link TransportMode#WEAPI}；未知返回 {@code null}
     */
    public static TransportMode transportHint(Context context) {
        String name = get(context).getString(KEY_TRANSPORT_HINT, null);
        if (TransportMode.WEAPI.name().equals(name)) {
            return TransportMode.WEAPI;
        }
        if (TransportMode.EAPI.name().equals(name)) {
            return TransportMode.EAPI;
        }
        return null;
    }

    public static void setTransportHint(Context context, TransportMode hint) {
        if (hint != TransportMode.EAPI && hint != TransportMode.WEAPI) {
            hint = null;
        }
        TransportMode current = transportHint(context);
        if (current == hint) {
            return;
        }
        get(context).edit()
                .putString(KEY_TRANSPORT_HINT, hint == null ? null : hint.name())
                .apply();
    }

    /** 贡献卡片是否展示封面，默认开启 */
    public static boolean showContributionCovers(Context context) {
        return get(context).getBoolean(KEY_SHOW_COVERS, true);
    }

    public static void setShowContributionCovers(Context context, boolean show) {
        get(context).edit().putBoolean(KEY_SHOW_COVERS, show).apply();
    }

    /** 「我的贡献」页是否展示接口通道卡片，默认展示 */
    public static boolean showTransportCard(Context context) {
        return get(context).getBoolean(KEY_SHOW_TRANSPORT_CARD, true);
    }

    public static void setShowTransportCard(Context context, boolean show) {
        get(context).edit().putBoolean(KEY_SHOW_TRANSPORT_CARD, show).apply();
    }

    /** 「我的贡献」页是否展示账号卡片，默认展示 */
    public static boolean showAccountCard(Context context) {
        return get(context).getBoolean(KEY_SHOW_ACCOUNT_CARD, true);
    }

    public static void setShowAccountCard(Context context, boolean show) {
        get(context).edit().putBoolean(KEY_SHOW_ACCOUNT_CARD, show).apply();
    }

    /** 「我的贡献」页是否展示贡献搜索模块，默认展示 */
    public static boolean showContributionSearch(Context context) {
        return get(context).getBoolean(KEY_SHOW_SEARCH, true);
    }

    public static void setShowContributionSearch(Context context, boolean show) {
        get(context).edit().putBoolean(KEY_SHOW_SEARCH, show).apply();
    }

    /** 贡献搜索卡片布局：normal 为竖向卡片，compact 为单行长条 */
    public static boolean compactSearchLayout(Context context) {
        return SEARCH_LAYOUT_COMPACT.equals(
                get(context).getString(KEY_SEARCH_LAYOUT, SEARCH_LAYOUT_NORMAL));
    }

    public static void setCompactSearchLayout(Context context, boolean compact) {
        get(context).edit()
                .putString(KEY_SEARCH_LAYOUT,
                        compact ? SEARCH_LAYOUT_COMPACT : SEARCH_LAYOUT_NORMAL)
                .apply();
    }

    public static void setTransportMode(Context context, TransportMode mode) {
        get(context).edit()
                .putString(KEY_TRANSPORT, (mode == null ? TransportMode.AUTO : mode).name())
                .apply();
    }

    /**
     * eapi 通道所需的设备标识：与原 APK 一致（大写 UUID 去掉连字符），
     * 首次访问时生成并持久化，避免每次请求都被服务端当作新设备。
     */
    public static synchronized String deviceId(Context context) {
        SharedPreferences prefs = get(context);
        String value = prefs.getString(KEY_DEVICE_ID, null);
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            prefs.edit().putString(KEY_DEVICE_ID, value).apply();
        }
        return value;
    }

    /** 最近一次成功上报的 FCM 令牌（空串表示尚未上报） */
    public static String fcmUploadedToken(Context context) {
        String v = get(context).getString(KEY_FCM_UPLOADED_TOKEN, "");
        return v == null ? "" : v;
    }

    public static void setFcmUploadedToken(Context context, String token) {
        get(context).edit().putString(KEY_FCM_UPLOADED_TOKEN,
                token == null ? "" : token).apply();
    }

    // ------------------------------------------------------------------
    // 发行公司历史（仿浏览器输入联想）
    // ------------------------------------------------------------------

    /** 发行公司历史输入记录，最近使用排在前面 */
    public static List<String> companyHistory(Context context) {
        List<String> out = new ArrayList<>();
        String raw = get(context).getString(KEY_COMPANY_HISTORY, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String value = arr.optString(i, "").trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        } catch (Exception ignored) {
            // 数据损坏时按空历史处理
        }
        return out;
    }

    /** 记住一次发行公司输入：去重（忽略大小写）、置顶、最多保留 12 条。 */
    public static void rememberCompany(Context context, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return;
        }
        List<String> history = companyHistory(context);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).equalsIgnoreCase(v)) {
                history.remove(i);
            }
        }
        history.add(0, v);
        while (history.size() > 12) {
            history.remove(history.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (String item : history) {
            arr.put(item);
        }
        get(context).edit().putString(KEY_COMPANY_HISTORY, arr.toString()).apply();
    }
}
