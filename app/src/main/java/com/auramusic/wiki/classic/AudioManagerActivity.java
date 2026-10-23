package com.auramusic.wiki.classic;

import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 音频文件管理：展示元信息，多选后迁移到指定文件夹或删除。 */
public class AudioManagerActivity extends BaseActivity {

    private RecyclerView recyclerView;
    private TextView empty;
    private TextView selectedLabel;
    private MaterialButton selectAll;
    private MaterialButton move;
    private MaterialButton delete;

    /** 底部操作栏：按外观偏好切换贴底 / 悬浮，玻璃材质由模糊层承载 */
    private View audioContent;
    private MaterialCardView bar;
    private BackdropBlurView barBlur;
    private QuickScrollBar quickScroll;

    private com.google.android.material.button.MaterialButtonToggleGroup toggleSort;
    private MaterialButton sortDirection;
    private LetterIndexBar letterIndex;

    private AudioFileAdapter adapter;
    private ActivityResultLauncher<Uri> pickTree;
    /** 与适配器保持同步的排序方向：true = 正序 */
    private boolean ascending;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "audio-manager");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_manager);
        bindToolbar(R.string.download_audio_title);

        recyclerView = findViewById(R.id.rv_audio);
        empty = findViewById(R.id.tv_audio_empty);
        selectedLabel = findViewById(R.id.tv_audio_selected);
        selectAll = findViewById(R.id.btn_audio_select_all);
        move = findViewById(R.id.btn_audio_move);
        delete = findViewById(R.id.btn_audio_delete);
        toggleSort = findViewById(R.id.toggle_audio_sort);
        sortDirection = findViewById(R.id.btn_sort_direction);
        letterIndex = findViewById(R.id.letter_index);
        audioContent = findViewById(R.id.audio_content);
        bar = findViewById(R.id.audio_bar);
        barBlur = findViewById(R.id.audio_bar_blur);

        adapter = new AudioFileAdapter(item -> updateActions());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        // 可拖动的滚动条：直接按比例跳到目标位置，而不是逐屏滚动
        quickScroll = findViewById(R.id.quick_scroll);
        quickScroll.attach(recyclerView);
        // 滚动时刷新底栏玻璃的采样，避免模糊内容停在上一屏
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (barBlur != null) {
                    barBlur.markDirty();
                }
            }
        });
        applyBarStyle();
        // 操作栏高度随字体 / 布局变化，重新测量后同步列表底部留白
        bar.addOnLayoutChangeListener((v, left, top, right, bottom,
                                       oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                syncBlurBounds();
                applyListInset();
            }
        });

        setupSorting();

        pickTree = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        moveSelected(uri);
                    }
                });

        selectAll.setOnClickListener(v -> {
            HapticsManager.tap(v);
            adapter.selectAll(!adapter.allSelected());
            updateActions();
        });
        move.setOnClickListener(v -> {
            HapticsManager.tap(v);
            if (adapter.selectedCount() == 0) {
                return;
            }
            pickTree.launch(null);
        });
        delete.setOnClickListener(v -> {
            HapticsManager.tap(v);
            if (adapter.selectedCount() == 0) {
                return;
            }
            confirmDelete();
        });
        updateActions();
        loadFiles();
    }

    /** 排序栏：时间 / 名称 / 大小 + 正倒序；名称排序时显示右侧字母索引。 */
    private void setupSorting() {
        toggleSort.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            AudioFileAdapter.Sort mode = checkedId == R.id.btn_sort_name
                    ? AudioFileAdapter.Sort.NAME
                    : checkedId == R.id.btn_sort_size
                    ? AudioFileAdapter.Sort.SIZE
                    : AudioFileAdapter.Sort.TIME;
            if (mode == adapter.sortMode() && ascending == adapter.ascending()) {
                return;
            }
            HapticsManager.toggle(group);
            adapter.setSort(mode, ascending);
            updateSortUi();
        });
        sortDirection.setOnClickListener(v -> {
            HapticsManager.tap(v);
            ascending = !ascending;
            adapter.setSort(adapter.sortMode(), ascending);
            updateSortUi();
        });
        letterIndex.setOnLetterListener((letter, index) -> {
            int position = adapter.indexOfLetter(letter);
            if (position < 0) {
                return;
            }
            RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
            if (manager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
            }
        });

        toggleSort.check(R.id.btn_sort_time);
        ascending = false;
        adapter.setSort(AudioFileAdapter.Sort.TIME, false);
        updateSortUi();
    }

    /** 同步排序控件与字母索引的可见状态。 */
    private void updateSortUi() {
        if (sortDirection != null) {
            sortDirection.setIconResource(ascending
                    ? R.drawable.ic_arrow_upward : R.drawable.ic_arrow_downward);
            sortDirection.setContentDescription(getString(ascending
                    ? R.string.download_audio_sort_asc : R.string.download_audio_sort_desc));
        }
        if (letterIndex == null) {
            return;
        }
        if (adapter.sortMode() != AudioFileAdapter.Sort.NAME) {
            letterIndex.setVisibility(View.GONE);
            return;
        }
        letterIndex.setLetters(adapter.letters());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「设置-外观」返回时底栏样式可能已变，就地重排而不重建页面
        applyBarStyle();
        loadFiles();
    }

    /**
     * 底部操作栏样式：与主界面底栏共用「贴底 / 悬浮」与玻璃材质设置。
     *
     * <p>悬浮：20dp 左右边距 + 8dp 底边距 + 28dp 圆角 + 3dp 投影；
     * 贴底：通栏直角、无投影；开启玻璃后卡片自身透明，模糊与蒙版交给
     * {@link BackdropBlurView}，与主界面底栏的材质完全一致。
     */
    private void applyBarStyle() {
        if (bar == null) {
            return;
        }
        boolean floating = AppearanceManager.isFloatingNav(this);
        int horizontal = floating
                ? getResources().getDimensionPixelSize(R.dimen.nav_float_margin_horizontal) : 0;
        int bottom = floating
                ? getResources().getDimensionPixelSize(R.dimen.nav_float_margin_bottom) : 0;
        int radius = floating
                ? getResources().getDimensionPixelSize(R.dimen.shape_nav) : 0;
        int glass = AppearanceManager.navGlassStyle(this);
        boolean glassOn = glass != AppearanceManager.GLASS_NONE;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        lp.setMarginStart(horizontal);
        lp.setMarginEnd(horizontal);
        lp.bottomMargin = bottom;
        bar.setLayoutParams(lp);
        bar.setRadius(radius);
        bar.setStrokeWidth(0);
        bar.setCardBackgroundColor(glassOn ? Color.TRANSPARENT
                : MaterialColors.getColor(bar, floating
                        ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                        : com.google.android.material.R.attr.colorSurfaceContainer));
        // 悬浮时施加阴影；玻璃材质自带高光与模糊，与主底栏一致不再叠投影
        bar.setCardElevation(floating && !glassOn
                ? getResources().getDimensionPixelSize(R.dimen.nav_float_elevation) : 0);

        if (barBlur != null) {
            ViewGroup.MarginLayoutParams blurLp =
                    (ViewGroup.MarginLayoutParams) barBlur.getLayoutParams();
            blurLp.setMarginStart(horizontal);
            blurLp.setMarginEnd(horizontal);
            blurLp.bottomMargin = bottom;
            barBlur.setLayoutParams(blurLp);
            barBlur.setCornerRadiusPx(radius);
            barBlur.setGlassStyle(glass, glassTint(glass), glassRim(glass));
            barBlur.setSourceView(audioContent);
            barBlur.markDirty();
        }
        bar.post(() -> {
            syncBlurBounds();
            applyListInset();
        });
    }

    /**
     * 模糊层高度必须显式等于操作栏（含下外边距）：若在 wrap_content 容器里用
     * {@code match_parent}，子视图会把容器撑满整个内容区，栏杆会被布局到顶部。
     */
    private void syncBlurBounds() {
        if (barBlur == null || bar == null || bar.getHeight() <= 0) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) barBlur.getLayoutParams();
        int height = bar.getHeight() + lp.bottomMargin;
        if (lp.height != height) {
            lp.height = height;
            barBlur.setLayoutParams(lp);
            barBlur.markDirty();
        }
    }

    /** 最后一条音频要能滚到操作栏之上：按栏杆高度给列表与滚动条补底部留白。 */
    private void applyListInset() {
        if (recyclerView == null || bar == null || bar.getHeight() <= 0) {
            return;
        }
        int barBottom = bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin : 0;
        int inset = bar.getHeight() + barBottom + px(8);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.screen_horizontal_margin);
        int top = getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        if (recyclerView.getPaddingTop() == top && recyclerView.getPaddingBottom() == inset) {
            return;
        }
        recyclerView.setPadding(horizontal, top, horizontal, inset);
        applyBottomMargin(quickScroll, inset);
        applyBottomMargin(letterIndex, inset);
    }

    private static void applyBottomMargin(View view, int marginPx) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (lp.bottomMargin == marginPx) {
            return;
        }
        lp.bottomMargin = marginPx;
        view.setLayoutParams(lp);
    }

    /** 玻璃蒙版色：亚克力更厚实，液态玻璃更通透（与主界面底栏同参数） */
    private int glassTint(int glass) {
        boolean liquid = glass == AppearanceManager.GLASS_LIQUID;
        int base = MaterialColors.getColor(bar, liquid
                ? com.google.android.material.R.attr.colorSurfaceContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh);
        return withAlpha(base, liquid ? 0.42f : 0.80f);
    }

    /** 玻璃高光描边：仅液态玻璃使用 */
    private int glassRim(int glass) {
        if (glass != AppearanceManager.GLASS_LIQUID) {
            return Color.TRANSPARENT;
        }
        return withAlpha(MaterialColors.getColor(bar,
                com.google.android.material.R.attr.colorOutline), 0.55f);
    }

    private static int withAlpha(int color, float alpha) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        return (color & 0x00FFFFFF) | (Math.round(255f * clamped) << 24);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void loadFiles() {
        executor.execute(() -> {
            List<AudioFileAdapter.Item> items = new ArrayList<>();
            for (File file : DownloadStore.audioFiles(this)) {
                items.add(new AudioFileAdapter.Item(file, file.length(),
                        file.lastModified(), durationOf(file)));
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                adapter.submit(items);
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                updateActions();
                updateSortUi();
            });
        });
    }

    /** 读取音频时长；失败时返回空串（不影响其它元信息展示）。 */
    private String durationOf(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (value == null) {
                return "";
            }
            long ms = Long.parseLong(value);
            long seconds = ms / 1000L;
            return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
        } catch (Exception e) {
            return "";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 部分机型对不支持的容器会在 release 时抛异常
            }
        }
    }

    private void updateActions() {
        int selected = adapter == null ? 0 : adapter.selectedCount();
        int total = adapter == null ? 0 : adapter.getItemCount();
        selectedLabel.setText(getString(R.string.download_audio_selected, selected, total));
        selectAll.setText(adapter != null && adapter.allSelected()
                ? R.string.download_audio_unselect_all : R.string.download_audio_select_all);
        boolean enabled = selected > 0;
        move.setEnabled(enabled);
        delete.setEnabled(enabled);
    }

    private void moveSelected(Uri tree) {
        final List<File> files = adapter.selectedFiles();
        if (files.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            String result = DownloadStore.moveToTree(this, files, tree);
            runOnUiThread(() -> {
                Feedback.show(findViewById(R.id.toolbar),
                        getString(R.string.download_audio_move_done, result));
                loadFiles();
            });
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.download_audio_delete)
                .setMessage(R.string.download_audio_delete_confirm)
                .setNegativeButton(R.string.au_action_cancel, null)
                .setPositiveButton(R.string.download_delete_action, (dialog, which) -> {
                    HapticsManager.confirm(findViewById(android.R.id.content));
                    deleteSelected();
                })
                .show();
    }

    private void deleteSelected() {
        final List<File> files = adapter.selectedFiles();
        if (files.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            int deleted = 0;
            for (File file : files) {
                if (file.delete()) {
                    deleted++;
                }
            }
            final int count = deleted;
            runOnUiThread(() -> {
                Feedback.show(findViewById(R.id.toolbar),
                        getString(R.string.download_audio_deleted, count));
                loadFiles();
            });
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }
}
