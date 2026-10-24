package com.auramusic.wiki.classic;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;

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

    /** 检查更新 / 下载的进行中状态与可取消句柄 */
    private boolean checkingUpdate;
    private Http.CancelToken checkToken;
    private Http.CancelToken downloadToken;
    private AlertDialog downloadDialog;
    private LinearProgressIndicator downloadBar;
    private TextView downloadLabel;

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
                checkForUpdate();
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

        if (rowUpdate != null && !checkingUpdate) {
            rowUpdate.setSupporting(getString(R.string.settings_summary_update,
                    UpdateChecker.currentVersion(this)));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (checkToken != null) {
            checkToken.cancel();
        }
        if (downloadToken != null) {
            downloadToken.cancel();
        }
        dismissDownloadDialog();
    }

    /** 手动检查最新正式版；结果通过提示或对话框呈现，不做后台轮询。 */
    private void checkForUpdate() {
        if (checkingUpdate || rowUpdate == null) {
            return;
        }
        checkingUpdate = true;
        rowUpdate.setEnabled(false);
        rowUpdate.setSupporting(getString(R.string.update_checking));
        checkToken = new Http.CancelToken();
        Http.CancelToken token = checkToken;
        new Thread(() -> {
            UpdateChecker.Release release = null;
            WikiException failure = null;
            try {
                release = UpdateChecker.latest(token);
            } catch (WikiException e) {
                failure = e;
            }
            UpdateChecker.Release result = release;
            WikiException error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                checkingUpdate = false;
                rowUpdate.setEnabled(true);
                rowUpdate.setSupporting(getString(R.string.settings_summary_update,
                        UpdateChecker.currentVersion(this)));
                if (error != null) {
                    Feedback.showLong(this, describeUpdateError(error));
                    return;
                }
                String current = UpdateChecker.currentVersion(this);
                if (result == null
                        || UpdateChecker.compareVersions(result.version, current) <= 0) {
                    Feedback.show(this, getString(R.string.update_latest, current));
                    return;
                }
                showReleaseDialog(result);
            });
        }, "update-check").start();
    }

    private String describeUpdateError(WikiException e) {
        switch (e.getKind()) {
            case NO_NETWORK:
                return getString(R.string.update_error_network);
            case TIMEOUT:
                return getString(R.string.update_error_timeout);
            case BAD_JSON:
                return getString(R.string.update_error_bad_json);
            case HTTP:
                if (e.getHttpCode() == 404) {
                    return getString(R.string.update_error_no_release);
                }
                if (e.getHttpCode() == 403) {
                    return getString(R.string.update_error_rate);
                }
                return getString(R.string.update_error_http, e.getHttpCode());
            default:
                return getString(R.string.update_check_failed,
                        e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private void showReleaseDialog(UpdateChecker.Release release) {
        if (release.apkUrl == null || release.apkUrl.isEmpty()) {
            String body = release.notes.isEmpty()
                    ? getString(R.string.update_no_apk)
                    : release.notes + "\n\n" + getString(R.string.update_no_apk);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.update_found_title, release.version))
                    .setMessage(body)
                    .setPositiveButton(R.string.update_later, null)
                    .show();
            return;
        }

        StringBuilder message = new StringBuilder();
        String notes = release.notes;
        if (notes.isEmpty()) {
            message.append(getString(R.string.update_notes_empty));
        } else {
            // 更新说明可能很长，超长部分截断，完整内容可到发布页查看
            if (notes.length() > 1200) {
                notes = notes.substring(0, 1200) + "…";
            }
            message.append(notes);
        }
        message.append("\n\n").append(getString(R.string.update_current_hint,
                UpdateChecker.currentVersion(this)));
        if (release.apkSize > 0) {
            message.append("\n").append(getString(R.string.update_size,
                    DownloadStore.formatSize(release.apkSize)));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.update_found_title, release.version))
                .setMessage(message)
                .setPositiveButton(R.string.update_download_install,
                        (dialog, which) -> startDownload(release))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private void startDownload(UpdateChecker.Release release) {
        if (!UpdateChecker.canInstall(this)) {
            showInstallPermissionDialog();
            return;
        }
        if (downloadDialog != null) {
            return;
        }
        downloadToken = new Http.CancelToken();
        Http.CancelToken token = downloadToken;
        showDownloadDialog();
        new Thread(() -> {
            File apk = null;
            WikiException failure = null;
            try {
                apk = UpdateChecker.download(this, release, percent -> runOnUiThread(() -> {
                    if (downloadBar != null) {
                        downloadBar.setProgressCompat(percent, true);
                    }
                    if (downloadLabel != null) {
                        downloadLabel.setText(
                                getString(R.string.update_download_percent, percent));
                    }
                }), token);
            } catch (WikiException e) {
                failure = e;
            }
            File result = apk;
            WikiException error = failure;
            runOnUiThread(() -> {
                dismissDownloadDialog();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (error != null) {
                    if (error.getKind() == WikiException.Kind.CANCELLED) {
                        Feedback.show(this, R.string.update_cancelled);
                    } else {
                        Feedback.showLong(this, getString(R.string.update_download_failed));
                    }
                    return;
                }
                installDownloaded(result);
            });
        }, "update-download").start();
    }

    private void showDownloadDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        int gap = Math.round(16 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8 * density), pad, 0);

        downloadLabel = new TextView(this);
        downloadLabel.setText(getString(R.string.update_download_percent, 0));
        downloadBar = new LinearProgressIndicator(this);
        downloadBar.setIndeterminate(false);
        downloadBar.setMax(100);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = gap;
        box.addView(downloadLabel);
        box.addView(downloadBar, params);

        downloadDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_download_title)
                .setView(box)
                // 下载期间禁止点外部/返回关闭，避免状态与按钮不一致；可点「取消」终止
                .setCancelable(false)
                .setNegativeButton(R.string.update_cancel, (dialog, which) -> cancelDownload())
                .create();
        downloadDialog.show();
    }

    private void cancelDownload() {
        if (downloadToken != null) {
            downloadToken.cancel();
        }
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null) {
            downloadDialog.dismiss();
            downloadDialog = null;
        }
        downloadBar = null;
        downloadLabel = null;
    }

    private void installDownloaded(File apk) {
        if (apk == null) {
            return;
        }
        if (!UpdateChecker.canInstall(this)) {
            showInstallPermissionDialog();
            return;
        }
        try {
            UpdateChecker.install(this, apk);
        } catch (Exception e) {
            Feedback.showLong(this, getString(R.string.update_install_failed));
        }
    }

    private void showInstallPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_install_title)
                .setMessage(R.string.update_install_desc)
                .setPositiveButton(R.string.update_install_settings,
                        (dialog, which) -> UpdateChecker.openInstallPermission(this))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private String stateLabel(boolean shown) {
        return getString(shown ? R.string.settings_state_shown : R.string.settings_state_hidden);
    }
}
