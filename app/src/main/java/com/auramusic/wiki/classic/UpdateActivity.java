package com.auramusic.wiki.classic;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;

/**
 * 设置二级页：检查更新。
 *
 * <p>与「未通过原因」「关于」同为二级页：工具栏 + 卡片化信息，不再用弹窗打断流程。
 * 进入即自动检查一次；发现新版本后在同一页展示版本号、更新说明与下载入口，
 * 下载进度沿用单曲上传的细条样式（圆角 2dp / 3dp 粗）与平滑推进。
 */
public class UpdateActivity extends BaseActivity {

    private static final int STATE_CHECKING = 0;
    private static final int STATE_LATEST = 1;
    private static final int STATE_AVAILABLE = 2;
    private static final int STATE_DOWNLOADING = 3;
    private static final int STATE_ERROR = 4;

    private Md3ListItemView rowCurrent;
    private Md3ListItemView rowLatest;
    private View notesGroup;
    private TextView tvState;
    private TextView tvDetail;
    private TextView tvNotes;
    private LinearProgressIndicator progress;
    private MaterialButton action;

    private Http.CancelToken token;
    private UpdateChecker.Release release;
    private int state = STATE_CHECKING;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_update);
        bindToolbar(R.string.settings_group_update);

        rowCurrent = findViewById(R.id.row_update_current);
        rowLatest = findViewById(R.id.row_update_latest);
        notesGroup = findViewById(R.id.group_update_notes);
        tvState = findViewById(R.id.tv_update_state);
        tvDetail = findViewById(R.id.tv_update_detail);
        tvNotes = findViewById(R.id.tv_update_notes);
        progress = findViewById(R.id.pb_update);
        action = findViewById(R.id.btn_update_action);

        rowCurrent.setTrailingValue(UpdateChecker.currentVersion(this));
        rowLatest.setTrailingValue(getString(R.string.update_value_unknown));
        SettingsRows.applyRowStyle(findViewById(R.id.group_update_info));

        action.setOnClickListener(v -> {
            HapticsManager.tap(v);
            onAction();
        });

        check();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelToken();
    }

    private void onAction() {
        switch (state) {
            case STATE_AVAILABLE:
                startDownload();
                break;
            case STATE_DOWNLOADING:
                cancelToken();
                break;
            default:
                check();
                break;
        }
    }

    private void check() {
        cancelToken();
        release = null;
        token = new Http.CancelToken();
        Http.CancelToken current = token;
        showChecking();
        new Thread(() -> {
            UpdateChecker.Release found = null;
            WikiException failure = null;
            try {
                found = UpdateChecker.latest(current);
            } catch (WikiException e) {
                failure = e;
            }
            UpdateChecker.Release result = found;
            WikiException error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || current != token) {
                    return;
                }
                if (error != null) {
                    showError(describe(error));
                    return;
                }
                String installed = UpdateChecker.currentVersion(this);
                if (result == null
                        || UpdateChecker.compareVersions(result.version, installed) <= 0) {
                    showLatest(result);
                } else {
                    showAvailable(result);
                }
            });
        }, "update-check").start();
    }

    private void showChecking() {
        state = STATE_CHECKING;
        rowLatest.setTrailingValue(getString(R.string.update_value_unknown));
        tvState.setText(R.string.update_status_checking);
        tvDetail.setText(R.string.update_detail_checking);
        notesGroup.setVisibility(View.GONE);
        showProgress(true, false);
        action.setText(R.string.update_action_recheck);
        action.setEnabled(false);
    }

    private void showLatest(UpdateChecker.Release found) {
        state = STATE_LATEST;
        rowLatest.setTrailingValue(found == null
                ? getString(R.string.update_value_unknown) : found.version);
        tvState.setText(R.string.update_status_latest);
        tvDetail.setText(R.string.update_detail_latest);
        notesGroup.setVisibility(View.GONE);
        showProgress(false, false);
        action.setText(R.string.update_action_recheck);
        action.setEnabled(true);
    }

    private void showAvailable(UpdateChecker.Release found) {
        state = STATE_AVAILABLE;
        release = found;
        rowLatest.setTrailingValue(found.version);
        tvState.setText(getString(R.string.update_status_available, found.version));
        notesGroup.setVisibility(View.VISIBLE);
        tvNotes.setText(found.notes.isEmpty()
                ? getString(R.string.update_notes_empty) : UpdateChecker.plainNotes(found.notes));
        showProgress(false, false);
        if (found.apkUrl == null) {
            tvDetail.setText(R.string.update_no_apk);
            action.setText(R.string.update_action_recheck);
            action.setEnabled(true);
            return;
        }
        tvDetail.setText(found.apkSize > 0
                ? getString(R.string.update_size, DownloadStore.formatSize(found.apkSize))
                : getString(R.string.update_detail_available));
        action.setText(R.string.update_action_download);
        action.setEnabled(true);
    }

    private void showError(String message) {
        state = STATE_ERROR;
        rowLatest.setTrailingValue(getString(R.string.update_value_unknown));
        tvState.setText(R.string.update_status_error);
        tvDetail.setText(message);
        notesGroup.setVisibility(View.GONE);
        showProgress(false, false);
        action.setText(R.string.update_action_retry);
        action.setEnabled(true);
    }

    private String describe(WikiException e) {
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

    private void startDownload() {
        final UpdateChecker.Release target = release;
        if (target == null || target.apkUrl == null) {
            return;
        }
        if (!UpdateChecker.canInstall(this)) {
            showInstallPermissionDialog();
            return;
        }
        cancelToken();
        token = new Http.CancelToken();
        Http.CancelToken current = token;

        state = STATE_DOWNLOADING;
        tvState.setText(R.string.update_status_downloading);
        tvDetail.setText(getString(R.string.update_download_percent, 0));
        showProgress(true, true);
        action.setText(R.string.update_action_cancel);
        action.setEnabled(true);

        new Thread(() -> {
            File apk = null;
            WikiException failure = null;
            try {
                apk = UpdateChecker.download(this, target, percent -> runOnUiThread(() -> {
                    if (current != token) {
                        return;
                    }
                    progress.setProgressCompat(percent, true);
                    tvDetail.setText(getString(R.string.update_download_percent, percent));
                }), current);
            } catch (WikiException e) {
                failure = e;
            }
            File result = apk;
            WikiException error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || current != token) {
                    return;
                }
                if (error != null) {
                    if (error.getKind() == WikiException.Kind.CANCELLED) {
                        Feedback.show(this, R.string.update_cancelled);
                        showAvailable(target);
                    } else {
                        state = STATE_ERROR;
                        tvState.setText(R.string.update_status_error);
                        tvDetail.setText(R.string.update_download_failed);
                        showProgress(false, false);
                        action.setText(R.string.update_action_retry);
                    }
                    return;
                }
                installDownloaded(result);
            });
        }, "update-download").start();
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
            state = STATE_LATEST;
            tvState.setText(R.string.update_status_installing);
            tvDetail.setText(R.string.update_detail_installing);
            showProgress(false, false);
            action.setText(R.string.update_action_recheck);
            action.setEnabled(true);
        } catch (Exception e) {
            state = STATE_ERROR;
            tvState.setText(R.string.update_status_error);
            tvDetail.setText(R.string.update_install_failed);
            showProgress(false, false);
            action.setText(R.string.update_action_retry);
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

    private void showProgress(boolean visible, boolean determinate) {
        if (progress == null) {
            return;
        }
        progress.setIndeterminate(!determinate);
        if (determinate) {
            progress.setProgressCompat(0, false);
        }
        MotionManager.animateVisibility(this, progress, visible);
    }

    private void cancelToken() {
        if (token != null) {
            token.cancel();
            token = null;
        }
    }
}
