package com.auramusic.wiki.classic;

import android.content.Intent;
import android.animation.ValueAnimator;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置二级页：下载与存储。
 *
 * <p>集中管理声云下载产生的封面与音频：展示占用、选择导出目录、按类清理、
 * 以及按用量阈值 / 时间周期自动清理；音频文件可进入独立列表做批量迁移。
 */
public class DownloadSettingsActivity extends BaseActivity {

    /** 阈值滑杆：0.5 GB ~ 10 GB，步进 0.5 GB */
    private static final float THRESHOLD_FROM_GB = 0.5f;
    private static final float THRESHOLD_TO_GB = 10f;
    private static final float THRESHOLD_STEP_GB = 0.5f;
    /** 周期滑杆：1 ~ 90 天 */
    private static final float PERIOD_FROM_DAYS = 1f;
    private static final float PERIOD_TO_DAYS = 90f;
    private static final float PERIOD_STEP_DAYS = 1f;

    private TextView total;
    private TextView statCoverSize;
    private TextView statCoverCount;
    private TextView statAudioSize;
    private TextView statAudioCount;
    private Md3ListItemView coverDir;
    private Md3ListItemView audioDir;
    private Md3ListItemView deleteCovers;
    private Md3ListItemView deleteAudio;
    private Md3ListItemView autoThreshold;
    private Md3ListItemView autoDays;
    private Md3ListItemView audioManager;

    private MaterialSwitch thresholdSwitch;
    private MaterialSwitch daysSwitch;
    /** 刷新取值时回填开关，避免触发监听造成「一进页面就被改写」 */
    private boolean syncing;

    private ActivityResultLauncher<Uri> pickCoverTree;
    private ActivityResultLauncher<Uri> pickAudioTree;

    /** 数字计数器：正在播放的动画与上一次展示值（-1 表示尚未展示过） */
    private ValueAnimator statsCounter;
    private long shownCoverBytes = -1L;
    private long shownAudioBytes = -1L;
    private int shownCoverCount = -1;
    private int shownAudioCount = -1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "download-settings");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download_settings);
        bindToolbar(R.string.download_settings_title);

        total = findViewById(R.id.tv_download_total);
        statCoverSize = findViewById(R.id.tv_stat_cover_size);
        statCoverCount = findViewById(R.id.tv_stat_cover_count);
        statAudioSize = findViewById(R.id.tv_stat_audio_size);
        statAudioCount = findViewById(R.id.tv_stat_audio_count);
        coverDir = findViewById(R.id.row_cover_dir);
        audioDir = findViewById(R.id.row_audio_dir);
        deleteCovers = findViewById(R.id.row_delete_covers);
        deleteAudio = findViewById(R.id.row_delete_audio);
        autoThreshold = findViewById(R.id.row_auto_threshold);
        autoDays = findViewById(R.id.row_auto_days);
        audioManager = findViewById(R.id.row_audio_manager);

        pickCoverTree = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        DownloadStore.setCustomTree(this, DownloadStore.KIND_COVER, uri);
                        refreshValues();
                    }
                });
        pickAudioTree = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        DownloadStore.setCustomTree(this, DownloadStore.KIND_AUDIO, uri);
                        refreshValues();
                    }
                });

        coverDir.setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickCoverTree.launch(null);
        });
        audioDir.setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickAudioTree.launch(null);
        });
        deleteCovers.setOnClickListener(v -> {
            HapticsManager.tap(v);
            confirmDelete(DownloadStore.KIND_COVER);
        });
        deleteAudio.setOnClickListener(v -> {
            HapticsManager.tap(v);
            confirmDelete(DownloadStore.KIND_AUDIO);
        });
        // 开关型可进入行：左侧内容区（到竖线为止）进入二级滑杆，右侧开关只负责启用 / 关闭
        thresholdSwitch = autoThreshold.setTrailingSwitchLink();
        thresholdSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncing) {
                return;
            }
            HapticsManager.toggle(button);
            DownloadStore.setAutoCleanThresholdEnabled(this, checked);
            refreshValues();
        });
        autoThreshold.setOnClickListener(v -> {
            if (DownloadStore.autoCleanThresholdMb(this) <= 0) {
                // 关闭状态不进入二级界面，避免用户以为改动会生效
                HapticsManager.reject(v);
                return;
            }
            HapticsManager.tap(v);
            showThresholdSheet();
        });

        daysSwitch = autoDays.setTrailingSwitchLink();
        daysSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncing) {
                return;
            }
            HapticsManager.toggle(button);
            DownloadStore.setAutoCleanDaysEnabled(this, checked);
            refreshValues();
        });
        autoDays.setOnClickListener(v -> {
            if (DownloadStore.autoCleanDays(this) <= 0) {
                HapticsManager.reject(v);
                return;
            }
            HapticsManager.tap(v);
            showPeriodSheet();
        });

        audioManager.setOnClickListener(v -> {
            HapticsManager.tap(v);
            startActivity(new Intent(this, AudioManagerActivity.class));
        });

        SettingsRows.applyRowStyle(findViewById(R.id.group_download_dirs));
        SettingsRows.applyRowStyle(findViewById(R.id.group_download_clean));
        SettingsRows.applyRowStyle(findViewById(R.id.group_download_audio));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
        refreshStats();
        DownloadStore.maybeAutoClean(this);
    }

    /** 目录与自动清理配置的即时刷新（不涉及磁盘扫描）。 */
    private void refreshValues() {
        // 目录以多行辅助说明完整展示（SAF 路径可能很长，尾部取值单行会截断）
        coverDir.setSupportingMultiline(4);
        coverDir.setSupporting(DownloadStore.displayDir(this, DownloadStore.KIND_COVER));
        coverDir.setTrailingValue("");
        audioDir.setSupportingMultiline(4);
        audioDir.setSupporting(DownloadStore.displayDir(this, DownloadStore.KIND_AUDIO));
        audioDir.setTrailingValue("");

        int mb = DownloadStore.autoCleanThresholdMb(this);
        int days = DownloadStore.autoCleanDays(this);
        syncing = true;
        thresholdSwitch.setChecked(mb > 0);
        daysSwitch.setChecked(days > 0);
        syncing = false;
        autoThreshold.setTrailingValue(thresholdLabel(mb));
        autoDays.setTrailingValue(periodLabel(days));
    }

    /** 目录大小递归统计放到后台线程，避免进入页面时掉帧。 */
    private void refreshStats() {
        executor.execute(() -> {
            long coverBytes = DownloadStore.sizeOf(this, DownloadStore.KIND_COVER);
            long audioBytes = DownloadStore.sizeOf(this, DownloadStore.KIND_AUDIO);
            int coverCount = DownloadStore.countFiles(DownloadStore.dir(this, DownloadStore.KIND_COVER));
            int audioCount = DownloadStore.countFiles(DownloadStore.dir(this, DownloadStore.KIND_AUDIO));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                animateStats(coverBytes, coverCount, audioBytes, audioCount);
            });
        });
    }

    /** 占用与文件数的计数器动效：从上次展示值滚到新值，读数过程符合 Expressive 的节奏。 */
    private void animateStats(long coverBytes, int coverCount, long audioBytes, int audioCount) {
        long fromCoverBytes = shownCoverBytes < 0L ? 0L : shownCoverBytes;
        long fromAudioBytes = shownAudioBytes < 0L ? 0L : shownAudioBytes;
        int fromCoverCount = shownCoverCount < 0 ? 0 : shownCoverCount;
        int fromAudioCount = shownAudioCount < 0 ? 0 : shownAudioCount;
        shownCoverBytes = coverBytes;
        shownAudioBytes = audioBytes;
        shownCoverCount = coverCount;
        shownAudioCount = audioCount;
        if (statsCounter != null) {
            statsCounter.cancel();
            statsCounter = null;
        }
        if (fromCoverBytes == coverBytes && fromAudioBytes == audioBytes
                && fromCoverCount == coverCount && fromAudioCount == audioCount) {
            renderStats(coverBytes, coverCount, audioBytes, audioCount);
            return;
        }
        statsCounter = MotionManager.counter(this, fraction -> renderStats(
                fromCoverBytes + Math.round((coverBytes - fromCoverBytes) * fraction),
                fromCoverCount + Math.round((coverCount - fromCoverCount) * fraction),
                fromAudioBytes + Math.round((audioBytes - fromAudioBytes) * fraction),
                fromAudioCount + Math.round((audioCount - fromAudioCount) * fraction)));
    }

    /** 按给定数值刷新概览与各行摘要（动画期间逐帧调用）。 */
    private void renderStats(long coverBytes, int coverCount, long audioBytes, int audioCount) {
        total.setText(DownloadStore.formatSize(coverBytes + audioBytes));
        statCoverSize.setText(DownloadStore.formatSize(coverBytes));
        statCoverCount.setText(getString(R.string.download_stat_files, coverCount));
        statAudioSize.setText(DownloadStore.formatSize(audioBytes));
        statAudioCount.setText(getString(R.string.download_stat_files, audioCount));
        audioManager.setSupporting(getString(R.string.download_audio_manager_summary,
                audioCount, DownloadStore.formatSize(audioBytes)));
        deleteCovers.setSupporting(getString(R.string.download_delete_covers_summary,
                DownloadStore.formatSize(coverBytes)));
        deleteAudio.setSupporting(getString(R.string.download_delete_audio_summary,
                DownloadStore.formatSize(audioBytes)));
    }

    private void confirmDelete(int kind) {
        boolean cover = kind == DownloadStore.KIND_COVER;
        new MaterialAlertDialogBuilder(this)
                .setTitle(cover ? R.string.download_delete_covers : R.string.download_delete_audio)
                .setMessage(cover ? R.string.download_delete_covers_confirm
                        : R.string.download_delete_audio_confirm)
                .setNegativeButton(R.string.au_action_cancel, null)
                .setPositiveButton(R.string.download_delete_action, (dialog, which) -> {
                    HapticsManager.confirm(findViewById(android.R.id.content));
                    executor.execute(() -> {
                        DownloadStore.deleteAll(this, kind);
                        runOnUiThread(() -> {
                            Feedback.show(findViewById(R.id.toolbar),
                                    getString(R.string.download_deleted));
                            refreshStats();
                        });
                    });
                })
                .show();
    }

    /** 二级界面：拖杆设置存储用量上限（GB），拖动时实时写入并刷新行内取值。 */
    private void showThresholdSheet() {
        int mb = DownloadStore.autoCleanThresholdMb(this);
        SliderSheet.show(this,
                getString(R.string.download_auto_threshold),
                getString(R.string.download_threshold_sheet_desc),
                THRESHOLD_FROM_GB, THRESHOLD_TO_GB, THRESHOLD_STEP_GB,
                Math.max(THRESHOLD_FROM_GB, mb / 1024f),
                value -> thresholdLabel(Math.round(value * 1024)),
                value -> {
                    DownloadStore.setAutoCleanThresholdMb(this, Math.round(value * 1024));
                    refreshValues();
                },
                this::maybeCleanNow);
    }

    /** 二级界面：拖杆设置定时清理周期（天）。 */
    private void showPeriodSheet() {
        int days = DownloadStore.autoCleanDays(this);
        SliderSheet.show(this,
                getString(R.string.download_auto_days),
                getString(R.string.download_period_sheet_desc),
                PERIOD_FROM_DAYS, PERIOD_TO_DAYS, PERIOD_STEP_DAYS,
                Math.max(PERIOD_FROM_DAYS, days),
                value -> periodLabel(Math.round(value)),
                value -> {
                    DownloadStore.setAutoCleanDays(this, Math.round(value));
                    refreshValues();
                },
                this::maybeCleanNow);
    }

    private void maybeCleanNow() {
        DownloadStore.maybeAutoClean(this);
        refreshStats();
    }

    private String thresholdLabel(int mb) {
        if (mb <= 0) {
            return getString(R.string.download_auto_off);
        }
        if (mb < 1024) {
            return mb + " MB";
        }
        float gb = mb / 1024f;
        // 0.5 GB 步进：整数值不带小数，其余保留一位
        String value = gb == Math.round(gb)
                ? String.valueOf(Math.round(gb))
                : String.format(Locale.getDefault(), "%.1f", gb);
        return value + " GB";
    }

    private String periodLabel(int days) {
        return days <= 0
                ? getString(R.string.download_auto_off)
                : getString(R.string.download_auto_days_value, days);
    }

    /** 页面销毁时结束后台线程池（任务本身很短，允许自然完成）。 */
    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
