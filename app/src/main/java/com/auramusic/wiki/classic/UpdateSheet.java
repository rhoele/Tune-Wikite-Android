package com.auramusic.wiki.classic;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;

/**
 * 应用内更新的交互层。
 *
 * <ul>
 *   <li>手动检查（设置 → 检查更新）：无新版本或失败用 Snackbar 提示，只有发现新版本才弹底部抽屉；</li>
 *   <li>启动静默检查：每天最多一次，无新版本完全不打扰；</li>
 *   <li>抽屉沿用「审核消息」「选择页码」的 M3 底部抽屉骨架，下载进度用单曲上传的细条样式。</li>
 * </ul>
 */
final class UpdateSheet {

    /** 静默检查的最小间隔：每天最多一次 */
    private static final long SILENT_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private final Activity activity;
    private final UpdateChecker.Release release;
    private final BottomSheetDialog dialog;
    private final TextView meta;
    private final TextView notes;
    private final TextView percent;
    private final LinearProgressIndicator progress;
    private final MaterialButton action;
    private final MaterialButton later;

    private Http.CancelToken token;
    private boolean downloading;

    private UpdateSheet(Activity activity, UpdateChecker.Release release) {
        this.activity = activity;
        this.release = release;

        LinearLayout root = (LinearLayout) LayoutInflater.from(activity)
                .inflate(R.layout.sheet_update, null);
        ((TextView) root.findViewById(R.id.tv_update_sheet_title))
                .setText(activity.getString(R.string.update_found_title, release.version));
        meta = root.findViewById(R.id.tv_update_sheet_meta);
        notes = root.findViewById(R.id.tv_update_sheet_notes);
        percent = root.findViewById(R.id.tv_update_sheet_percent);
        progress = root.findViewById(R.id.pb_update_sheet);
        action = root.findViewById(R.id.btn_update_action);
        later = root.findViewById(R.id.btn_update_later);

        String current = UpdateChecker.currentVersion(activity);
        meta.setText(release.apkSize > 0
                ? activity.getString(R.string.update_sheet_meta, current,
                DownloadStore.formatSize(release.apkSize))
                : activity.getString(R.string.update_sheet_meta_plain, current));

        String body = release.notes.isEmpty()
                ? activity.getString(R.string.update_notes_empty)
                : UpdateChecker.plainNotes(release.notes);
        if (release.apkUrl == null) {
            body = body + "\n\n" + activity.getString(R.string.update_no_apk);
            action.setEnabled(false);
        }
        notes.setText(body);

        dialog = new BottomSheetDialog(activity);
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            downloading = false;
            if (token != null) {
                token.cancel();
                token = null;
            }
        });

        later.setOnClickListener(v -> dialog.dismiss());
        action.setOnClickListener(v -> {
            if (downloading) {
                cancelDownload();
            } else {
                startDownload();
            }
        });
    }

    // ------------------------------------------------------------------
    // 对外的三个入口
    // ------------------------------------------------------------------

    /** 手动检查：结束回调先于结果提示执行，便于调用方恢复行状态。 */
    static void checkManually(Activity activity, @Nullable Runnable onFinished) {
        new Thread(() -> {
            UpdateChecker.Release found = null;
            WikiException failure = null;
            try {
                found = UpdateChecker.latest(new Http.CancelToken());
            } catch (WikiException e) {
                failure = e;
            }
            UpdateChecker.Release result = found;
            WikiException error = failure;
            activity.runOnUiThread(() -> {
                if (onFinished != null) {
                    onFinished.run();
                }
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (error != null) {
                    Feedback.showLong(activity, describe(activity, error));
                    return;
                }
                String current = UpdateChecker.currentVersion(activity);
                if (result == null
                        || UpdateChecker.compareVersions(result.version, current) <= 0) {
                    Feedback.show(activity, activity.getString(R.string.update_latest, current));
                    return;
                }
                show(activity, result);
            });
        }, "update-check").start();
    }

    /** 启动时静默检查：每天最多一次，只有发现新版本才提示。 */
    static void checkSilently(Activity activity) {
        long now = System.currentTimeMillis();
        if (now - WikiPrefs.updateCheckedAt(activity) < SILENT_CHECK_INTERVAL_MS) {
            return;
        }
        // 记录尝试时间：网络不通时也不会在同一天反复请求
        WikiPrefs.setUpdateCheckedAt(activity, now);
        new Thread(() -> {
            UpdateChecker.Release found;
            try {
                found = UpdateChecker.latest(new Http.CancelToken());
            } catch (WikiException e) {
                return;
            }
            final UpdateChecker.Release result = found;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                String current = UpdateChecker.currentVersion(activity);
                if (result != null
                        && UpdateChecker.compareVersions(result.version, current) > 0) {
                    show(activity, result);
                }
            });
        }, "update-check-silent").start();
    }

    static void show(Activity activity, UpdateChecker.Release release) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        new UpdateSheet(activity, release).present();
    }

    private void present() {
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    // ------------------------------------------------------------------
    // 下载与安装
    // ------------------------------------------------------------------

    private void startDownload() {
        if (release.apkUrl == null) {
            return;
        }
        if (!UpdateChecker.canInstall(activity)) {
            showInstallPermissionDialog();
            return;
        }
        downloading = true;
        token = new Http.CancelToken();
        Http.CancelToken current = token;

        progress.setVisibility(View.VISIBLE);
        progress.setProgressCompat(0, false);
        percent.setVisibility(View.VISIBLE);
        percent.setText(activity.getString(R.string.update_download_percent, 0));
        later.setVisibility(View.GONE);
        action.setText(R.string.update_action_cancel);

        new Thread(() -> {
            File apk = null;
            WikiException failure = null;
            try {
                apk = UpdateChecker.download(activity, release, value ->
                        activity.runOnUiThread(() -> {
                            if (current != token || !downloading) {
                                return;
                            }
                            progress.setProgressCompat(value, true);
                            percent.setText(
                                    activity.getString(R.string.update_download_percent, value));
                        }), current);
            } catch (WikiException e) {
                failure = e;
            }
            File result = apk;
            WikiException error = failure;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (current != token) {
                    return;
                }
                if (error != null) {
                    downloading = false;
                    if (error.getKind() == WikiException.Kind.CANCELLED) {
                        Feedback.show(activity, R.string.update_cancelled);
                        resetActions(R.string.update_action_download);
                    } else {
                        Feedback.showLong(activity,
                                activity.getString(R.string.update_download_failed));
                        resetActions(R.string.update_action_retry);
                    }
                    return;
                }
                dialog.dismiss();
                try {
                    UpdateChecker.install(activity, result);
                } catch (Exception e) {
                    Feedback.showLong(activity,
                            activity.getString(R.string.update_install_failed));
                }
            });
        }, "update-download").start();
    }

    private void cancelDownload() {
        if (token != null) {
            token.cancel();
        }
    }

    private void resetActions(int actionText) {
        progress.setVisibility(View.GONE);
        percent.setVisibility(View.GONE);
        later.setVisibility(View.VISIBLE);
        action.setText(actionText);
    }

    private void showInstallPermissionDialog() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_install_title)
                .setMessage(R.string.update_install_desc)
                .setPositiveButton(R.string.update_install_settings,
                        (dialog, which) -> UpdateChecker.openInstallPermission(activity))
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    private static String describe(Activity activity, WikiException e) {
        switch (e.getKind()) {
            case NO_NETWORK:
                return activity.getString(R.string.update_error_network);
            case TIMEOUT:
                return activity.getString(R.string.update_error_timeout);
            case BAD_JSON:
                return activity.getString(R.string.update_error_bad_json);
            case HTTP:
                if (e.getHttpCode() == 404) {
                    return activity.getString(R.string.update_error_no_release);
                }
                if (e.getHttpCode() == 403) {
                    return activity.getString(R.string.update_error_rate);
                }
                return activity.getString(R.string.update_error_http, e.getHttpCode());
            default:
                return activity.getString(R.string.update_check_failed,
                        e.getMessage() == null ? "" : e.getMessage());
        }
    }
}
