package com.auramusic.wiki.classic;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/**
 * 设置二级页：后台审核提醒（需求 1）。
 *
 * <p>页面按「功能开关 / 轮询设置 / 后台保活」三级分类组织：
 * <ul>
 *   <li>开关即时生效：切换后立即启动 / 停止后台轮询并持久化；</li>
 *   <li>轮询周期使用 MD3 {@link Slider}，拖动时实时显示取值（含预计请求量），
 *       松手才写入配置并重启轮询，避免拖动过程中反复重启服务。</li>
 * </ul>
 */
public class AuditSettingsActivity extends BaseActivity {

    private static final String TAG = "AuditSwitch";

    private MaterialSwitch switchAudit;
    private Slider sliderInterval;
    private TextView tvIntervalValue;
    private TextView tvIntervalCost;
    private Md3ListItemView rowBatteryOpt;

    /** 初始化回填期间抑制监听回调，避免把回填当成用户操作 */
    private boolean binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_audit);
        bindToolbar(R.string.settings_group_audit);

        switchAudit = switchOf(R.id.switch_background_audit);

        binding = true;
        if (switchAudit != null) {
            switchAudit.setChecked(WikiPrefs.backgroundAuditEnabled(this));
        }
        binding = false;

        if (switchAudit != null) {
            switchAudit.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (binding) {
                    return;
                }
                HapticsManager.toggle(buttonView);
                WikiPrefs.get(this).edit().putBoolean(WikiPrefs.KEY_BG_AUDIT, isChecked).apply();
                boolean loggedIn = SessionManager.isLoggedIn(this);
                Log.i(TAG, "后台审核提醒切换为 " + isChecked
                        + "，登录态=" + loggedIn + "，有凭据=" + WikiPrefs.hasCookie(this));
                if (isChecked) {
                    if (loggedIn) {
                        AuditPollingService.restart(this);
                        Log.i(TAG, "已重启后台轮询服务");
                    } else {
                        Feedback.showLong(AuditSettingsActivity.this,
                                getString(R.string.settings_cookie_need_login));
                    }
                } else {
                    AuditPollingService.stop(this);
                    Log.i(TAG, "已停止后台轮询服务");
                }
            });
        }

        setupIntervalSlider();

        // 后台保活：电池优化豁免 + 厂商自启动入口（布局中已是标准列表行）
        rowBatteryOpt = findViewById(R.id.row_battery_opt);
        rowBatteryOpt.setTrailingValue(batteryOptValue().value());
        rowBatteryOpt.setOnClickListener(v -> {
            HapticsManager.tap(v);
            KeepAliveHelper.requestIgnoreBatteryOptimizations(this);
        });

        Md3ListItemView rowOem = findViewById(R.id.row_oem_autostart);
        rowOem.setTrailingValue(getString(R.string.settings_oem_action));
        // 把识别到的系统写进说明，用户能直接看到当前是 ColorOS 还是 OxygenOS
        rowOem.setSupporting(getString(R.string.settings_oem_autostart_desc_os,
                KeepAliveHelper.osName()));
        rowOem.setOnClickListener(v -> {
            HapticsManager.tap(v);
            if (!KeepAliveHelper.openAutoStartSettings(this)) {
                showAutoStartGuide();
            }
        });

        // 按「设置项样式」偏好重排：独立卡片模式下每行单独成卡，分组卡透明化
        refreshAuditRowSupporting();
        SettingsRows.applyRowStyle(findViewById(R.id.group_audit_switch));
        SettingsRows.applyRowStyle(findViewById(R.id.group_keep_alive));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统设置返回，刷新豁免状态
        rowBatteryOpt.setTrailingValue(batteryOptValue().value());
        // 开关状态以配置为准，避免在其它入口改动后本页显示不同步
        binding = true;
        if (switchAudit != null) {
            switchAudit.setChecked(WikiPrefs.backgroundAuditEnabled(this));
        }
        binding = false;
        syncIntervalSlider();
        // 可能在其它入口切换了账号，说明文字需要跟着登录态变化
        refreshAuditRowSupporting();
    }

    /**
     * 厂商把自启动列表页设为私有时（如 ColorOS 16 / OxygenOS 16 要求签名级权限），
     * 第三方应用无法直接拉起。此时说明手动路径，并提供跳转到应用详情的入口，
     * 而不是无声地把用户带到无关页面。
     */
    private void showAutoStartGuide() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_oem_autostart_title)
                .setMessage(getString(R.string.settings_oem_autostart_guide,
                        KeepAliveHelper.osName()))
                .setNegativeButton(R.string.au_action_cancel, null)
                .setPositiveButton(R.string.settings_oem_autostart_open_details, (dialog, which) -> {
                    HapticsManager.tap(findViewById(android.R.id.content));
                    KeepAliveHelper.openAppDetails(this);
                })
                .show();
    }

    private void refreshAuditRowSupporting() {
        Md3ListItemView row = findViewById(R.id.switch_background_audit);
        if (row == null) {
            return;
        }
        // 登录态不同，说明文字不同：未登录时提示先登录，已登录时说明提醒范围
        row.setSupporting(getString(SessionManager.isLoggedIn(this)
                ? R.string.settings_bg_audit_desc
                : R.string.settings_bg_audit_need_login));
    }

    private SettingsRows.ValueProvider batteryOptValue() {
        return () -> getString(KeepAliveHelper.isBatteryOptimizationIgnored(this)
                ? R.string.settings_battery_opt_yes
                : R.string.settings_battery_opt_no);
    }

    /** 从列表行里取出尾部开关（Stage 2 起开关由 Md3ListItemView 统一承载）。 */
    private MaterialSwitch switchOf(int rowId) {
        View row = findViewById(rowId);
        return row instanceof Md3ListItemView ? ((Md3ListItemView) row).getSwitch() : null;
    }

    // ------------------------------------------------------------------
    // 轮询周期：MD3 Slider
    // ------------------------------------------------------------------

    private void setupIntervalSlider() {
        sliderInterval = findViewById(R.id.slider_interval);
        tvIntervalValue = findViewById(R.id.tv_interval_value);
        tvIntervalCost = findViewById(R.id.tv_interval_cost);
        if (sliderInterval == null) {
            return;
        }
        int minutes = snapInterval(WikiPrefs.pollingIntervalMinutes(this));
        sliderInterval.setValue(minutes);
        renderInterval(minutes);

        sliderInterval.addOnChangeListener((slider, value, fromUser) ->
                renderInterval(snapInterval((int) value)));
        sliderInterval.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                // 松手才落盘：拖动过程中每一格都重启服务会造成大量无谓的请求与动画抖动
                applyInterval(snapInterval((int) slider.getValue()));
            }
        });
    }

    /** 以配置为准回填滑块（其它入口或历史配置可能不在步长上）。 */
    private void syncIntervalSlider() {
        if (sliderInterval == null) {
            return;
        }
        int minutes = snapInterval(WikiPrefs.pollingIntervalMinutes(this));
        if (sliderInterval.getValue() != minutes) {
            sliderInterval.setValue(minutes);
        }
        renderInterval(minutes);
    }

    private void renderInterval(int minutes) {
        if (tvIntervalValue != null) {
            tvIntervalValue.setText(getString(R.string.settings_interval_minutes, minutes));
        }
        if (tvIntervalCost != null) {
            // 每轮 3 个请求；12 小时 = 720 / 周期 轮
            int rounds = Math.max(1, 720 / Math.max(1, minutes));
            tvIntervalCost.setText(getString(R.string.settings_audit_interval_cost, rounds * 3));
        }
    }

    private void applyInterval(int minutes) {
        if (minutes == WikiPrefs.pollingIntervalMinutes(this)) {
            return;
        }
        WikiPrefs.get(this).edit().putInt(WikiPrefs.KEY_INTERVAL, minutes).apply();
        Log.i(TAG, "轮询周期更新为 " + minutes + " 分钟，后台提醒="
                + WikiPrefs.backgroundAuditEnabled(this)
                + "，登录态=" + SessionManager.isLoggedIn(this));
        Feedback.show(this, getString(R.string.settings_interval_saved, minutes));
        // 常驻服务按旧周期 sleep，改周期后必须重启才会立即生效
        if (WikiPrefs.backgroundAuditEnabled(this) && SessionManager.isLoggedIn(this)) {
            AuditPollingService.restart(this);
            Log.i(TAG, "轮询周期变更，已重启后台轮询服务");
        }
    }

    /** 对齐到 Slider 的步长，并夹在 [MIN, MAX] 区间内。 */
    private static int snapInterval(int minutes) {
        int min = WikiPrefs.MIN_INTERVAL_MINUTES;
        int max = WikiPrefs.MAX_INTERVAL_MINUTES;
        int step = Math.max(1, WikiPrefs.INTERVAL_STEP_MINUTES);
        int clamped = Math.min(max, Math.max(min, minutes));
        int snapped = min + Math.round((clamped - min) / (float) step) * step;
        return Math.min(max, Math.max(min, snapped));
    }
}
