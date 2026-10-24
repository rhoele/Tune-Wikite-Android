package com.auramusic.wiki.classic;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

/**
 * 设置一级目录（需求 4 / Stage 2）。
 *
 * <p>结构由「6 张大卡片」改为「3 个分组 + MD3 列表行」：
 * 通用（外观 / 显示）、账号与提醒（账号 / 通知 / 后台审核提醒）、其他（检查更新 / 关于）。
 * 入口与跳转目标完全不变；摘要由 {@link Md3ListItemView#setSupporting} 实时刷新。
 */
public class SettingsActivity extends BaseActivity {

    private Md3ListItemView rowAppearance;
    private Md3ListItemView rowDisplay;
    private Md3ListItemView rowNotification;
    private Md3ListItemView rowAccount;
    private Md3ListItemView rowAudit;
    private Md3ListItemView rowRejectReasons;
    private Md3ListItemView rowDownload;
    private Md3ListItemView rowUpdate;
    /** 本次创建时采用的「设置项样式」，用于从外观页返回后判断是否需要重建 */
    private boolean appliedAsCards;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        bindToolbar(R.string.settings_title);

        rowAppearance = findViewById(R.id.entry_appearance);
        rowDisplay = findViewById(R.id.entry_display);
        rowNotification = findViewById(R.id.entry_notification);
        rowAccount = findViewById(R.id.entry_account);
        rowAudit = findViewById(R.id.entry_audit);
        rowRejectReasons = findViewById(R.id.entry_reject_reasons);
        rowDownload = findViewById(R.id.entry_download);
        rowUpdate = findViewById(R.id.entry_update);

        openOnClick(R.id.entry_appearance, AppearanceSettingsActivity.class);
        openOnClick(R.id.entry_display, DisplaySettingsActivity.class);
        openOnClick(R.id.entry_notification, NotificationSettingsActivity.class);
        openOnClick(R.id.entry_account, AccountSettingsActivity.class);
        openOnClick(R.id.entry_audit, AuditSettingsActivity.class);
        openOnClick(R.id.entry_reject_reasons, RejectReasonsActivity.class);
        openOnClick(R.id.entry_download, DownloadSettingsActivity.class);
        openOnClick(R.id.entry_about, AboutSettingsActivity.class);

        if (rowUpdate != null) {
            rowUpdate.setSupporting(getString(R.string.settings_summary_update,
                    UpdateChecker.currentVersion(this)));
            rowUpdate.setOnClickListener(v -> {
                HapticsManager.tap(v);
                checkUpdate(v);
            });
        }

        // 按「设置项样式」偏好重排：独立卡片模式下每行单独成卡，分组卡透明化
        appliedAsCards = AppearanceManager.rowsAsCards(this);
        SettingsRows.applyRowStyle(findViewById(R.id.group_general));
        SettingsRows.applyRowStyle(findViewById(R.id.group_download));
        SettingsRows.applyRowStyle(findViewById(R.id.group_account_notify));
        SettingsRows.applyRowStyle(findViewById(R.id.group_other));
        // 启动时顺带检查下载目录的自动清理条件
        DownloadStore.maybeAutoClean(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观页可以改「设置项样式」；返回本页时结构不会自动变，需要重建一次
        if (appliedAsCards != AppearanceManager.rowsAsCards(this)) {
            recreate();
            return;
        }
        updateSummaries();
    }

    private void openOnClick(int viewId, Class<?> target) {
        View entry = findViewById(viewId);
        if (entry != null) {
            entry.setOnClickListener(v -> {
                HapticsManager.tap(v);
                startActivity(new Intent(this, target));
            });
        }
    }

    /** 二级页可能改动状态，回到一级页时重算摘要，保证展示与配置一致。 */
    private void updateSummaries() {
        if (rowAppearance != null) {
            String appearance = getString(R.string.settings_summary_appearance,
                    getString(AppearanceManager.themeLabelRes(AppearanceManager.themeMode(this))),
                    getString(AppearanceManager.paletteLabelRes(AppearanceManager.paletteKey(
                            AppearanceManager.palette(this), AppearanceManager.variant(this)))),
                    getString(AppearanceManager.fontLabelRes(AppearanceManager.fontScale(this))));
            String haptics = AppearanceManager.hapticsEnabled(this)
                    ? getString(AppearanceManager.hapticLevelLabelRes(AppearanceManager.hapticLevel(this)))
                    : getString(R.string.appearance_state_off);
            String experience = getString(R.string.settings_summary_experience,
                    getString(AppearanceManager.motionLabelRes(AppearanceManager.motionScale(this))),
                    haptics,
                    getString(AppearanceManager.navStyleLabelRes(AppearanceManager.navStyle(this))));
            rowAppearance.setSupporting(getString(R.string.settings_summary_appearance_stacked,
                    appearance, experience));
        }

        if (rowDisplay != null) {
            String covers = getString(WikiPrefs.showContributionCovers(this)
                    ? R.string.settings_summary_display_on
                    : R.string.settings_summary_display_off);
            String cards = getString(R.string.settings_summary_display_cards,
                    stateLabel(WikiPrefs.showTransportCard(this)),
                    stateLabel(WikiPrefs.showAccountCard(this)),
                    stateLabel(WikiPrefs.showContributionSearch(this)));
            rowDisplay.setSupporting(getString(R.string.settings_summary_display_stacked, covers, cards));
        }

        if (rowNotification != null) {
            boolean enabled;
            try {
                enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
            } catch (Exception e) {
                enabled = true;
            }
            rowNotification.setSupporting(getString(enabled
                    ? R.string.settings_summary_notification_on
                    : R.string.settings_summary_notification_off));
        }

        if (rowAccount != null) {
            if (SessionManager.isLoggedIn(this)) {
                String name = SessionManager.displayName(this);
                if (name.isEmpty()) {
                    name = getString(R.string.login_account_unknown);
                }
                rowAccount.setSupporting(getString(R.string.settings_summary_account_on, name));
            } else {
                rowAccount.setSupporting(getString(R.string.settings_summary_account_off));
            }
        }

        if (rowAudit != null) {
            rowAudit.setSupporting(WikiPrefs.backgroundAuditEnabled(this)
                    ? getString(R.string.settings_summary_audit_on,
                    WikiPrefs.pollingIntervalMinutes(this))
                    : getString(R.string.settings_summary_audit_off));
        }

        if (rowRejectReasons != null) {
            int count = RejectArchive.entries(this).size();
            rowRejectReasons.setSupporting(count == 0
                    ? getString(R.string.settings_reject_reasons_desc)
                    : getString(R.string.settings_reject_reasons_count,
                    count, RejectArchive.groups(this).size()));
        }

        if (rowDownload != null) {
            // 目录扫描放到后台，避免设置页首帧被磁盘 IO 拖慢
            new Thread(() -> {
                long coverBytes = DownloadStore.sizeOf(this, DownloadStore.KIND_COVER);
                long audioBytes = DownloadStore.sizeOf(this, DownloadStore.KIND_AUDIO);
                int covers = DownloadStore.countFiles(
                        DownloadStore.dir(this, DownloadStore.KIND_COVER));
                int tracks = DownloadStore.countFiles(
                        DownloadStore.dir(this, DownloadStore.KIND_AUDIO));
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        rowDownload.setSupporting(getString(R.string.settings_summary_download,
                                DownloadStore.formatSize(coverBytes + audioBytes),
                                covers, tracks));
                    }
                });
            }, "settings-download").start();
        }

        if (rowUpdate != null) {
            rowUpdate.setSupporting(getString(R.string.settings_summary_update,
                    UpdateChecker.currentVersion(this)));
        }
    }

    /** 手动检查：期间把行切到「正在检查更新…」，结束后恢复版本摘要。 */
    private void checkUpdate(View anchor) {
        if (rowUpdate == null) {
            return;
        }
        rowUpdate.setEnabled(false);
        rowUpdate.setSupporting(getString(R.string.update_checking));
        UpdateSheet.checkManually(this, () -> {
            rowUpdate.setEnabled(true);
            rowUpdate.setSupporting(getString(R.string.settings_summary_update,
                    UpdateChecker.currentVersion(this)));
        });
    }

    private String stateLabel(boolean shown) {
        return getString(shown ? R.string.settings_state_shown : R.string.settings_state_hidden);
    }
}
