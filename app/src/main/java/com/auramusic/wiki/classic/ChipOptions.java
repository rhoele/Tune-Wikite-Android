package com.auramusic.wiki.classic;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 语种 / 曲风多选条的构建。
 *
 * <p>两者的选项都可能超过一屏：默认只显示常用项与已选项，其余收进「更多 / 展开全部」，
 * 避免表单被几十个 chip 撑长；已选项在任何折叠状态下都必须可见，否则用户看不到自己选了什么。
 *
 * <p>语种还有一条与接口一致的硬约束：「未知 / 其他 / 纯音乐」互斥且只能单独选择。
 */
final class ChipOptions {

    /** 曲风折叠时显示的条数 */
    private static final int TAG_VISIBLE_LIMIT = 12;

    private ChipOptions() {
    }

    /** 语种多选。 */
    static void bindLanguages(ChipGroup group, Set<String> selected, boolean expanded,
                              Runnable onToggleExpand) {
        if (group == null || selected == null) {
            return;
        }
        group.removeAllViews();
        // 互斥项被选中时要取消其它 chip，用该标记阻断回调重入
        final boolean[] guard = {false};
        for (String language : visibleLanguages(selected, expanded)) {
            Chip chip = newChip(group.getContext(), language);
            chip.setChecked(selected.contains(language));
            chip.setOnCheckedChangeListener((buttonView, checked) -> {
                if (guard[0]) {
                    return;
                }
                AlbumDraft.toggleLanguage(selected, language, checked);
                syncChecked(group, selected, guard);
            });
            group.addView(chip);
        }
        group.addView(expandChip(group.getContext(), expanded,
                R.string.au_options_more_languages, onToggleExpand));
    }

    /**
     * 曲风多选。
     *
     * @param max 最多可选数量（与原 APK 一致为 3）
     */
    static void bindTags(ChipGroup group, List<AlbumDraft.Tag> tags,
                         Set<AlbumDraft.Tag> selected, int max, boolean expanded,
                         Runnable onToggleExpand) {
        if (group == null || selected == null) {
            return;
        }
        group.removeAllViews();
        if (tags == null || tags.isEmpty()) {
            return;
        }
        List<AlbumDraft.Tag> visible = new ArrayList<>();
        if (expanded || tags.size() <= TAG_VISIBLE_LIMIT) {
            visible.addAll(tags);
        } else {
            for (int i = 0; i < tags.size(); i++) {
                AlbumDraft.Tag tag = tags.get(i);
                if (i < TAG_VISIBLE_LIMIT || selected.contains(tag)) {
                    visible.add(tag);
                }
            }
        }
        for (AlbumDraft.Tag tag : visible) {
            Chip chip = newChip(group.getContext(), tag.name);
            chip.setChecked(selected.contains(tag));
            chip.setOnCheckedChangeListener((buttonView, checked) -> {
                if (!checked) {
                    selected.remove(tag);
                    return;
                }
                if (selected.size() >= max) {
                    // 超限：立刻取消勾选，本次选择不生效
                    buttonView.setChecked(false);
                    Feedback.show(group, R.string.au_tag_limit);
                    return;
                }
                selected.add(tag);
            });
            group.addView(chip);
        }
        if (tags.size() > TAG_VISIBLE_LIMIT) {
            group.addView(expandChip(group.getContext(), expanded,
                    R.string.au_options_more_tags, onToggleExpand));
        }
    }

    /** 在标签组末尾插入「加载中 / 加载失败」提示 chip（不可勾选）。 */
    static void showHint(ChipGroup group, int textRes, View.OnClickListener onClick) {
        if (group == null) {
            return;
        }
        group.removeAllViews();
        Chip chip = newChip(group.getContext(), group.getContext().getString(textRes));
        chip.setCheckable(false);
        chip.setClickable(onClick != null);
        if (onClick != null) {
            chip.setOnClickListener(onClick);
        }
        group.addView(chip);
    }

    // ------------------------------------------------------------------

    /** 折叠时：常用语种 + 已选语种，并保持与完整列表相同的顺序。 */
    private static List<String> visibleLanguages(Set<String> selected, boolean expanded) {
        if (expanded) {
            return AlbumDraft.LANGUAGES;
        }
        List<String> out = new ArrayList<>();
        for (String language : AlbumDraft.LANGUAGES) {
            if (AlbumDraft.CORE_LANGUAGES.contains(language) || selected.contains(language)) {
                out.add(language);
            }
        }
        return out;
    }

    private static void syncChecked(ChipGroup group, Set<String> selected, boolean[] guard) {
        guard[0] = true;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof Chip)) {
                continue;
            }
            Chip chip = (Chip) child;
            String text = String.valueOf(chip.getText());
            if (AlbumDraft.LANGUAGES.contains(text)) {
                chip.setChecked(selected.contains(text));
            }
        }
        guard[0] = false;
    }

    private static Chip newChip(Context context, String text) {
        Chip chip = new Chip(context);
        chip.setText(text);
        chip.setCheckable(true);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setMinHeight(Math.round(40f * context.getResources().getDisplayMetrics().density));
        return chip;
    }

    private static Chip expandChip(Context context, boolean expanded, int moreRes,
                                   Runnable onToggle) {
        Chip chip = newChip(context, context.getString(
                expanded ? R.string.au_options_collapse : moreRes));
        chip.setCheckable(false);
        chip.setChecked(false);
        chip.setOnClickListener(v -> {
            if (onToggle != null) {
                onToggle.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chip.setLayoutParams(lp);
        return chip;
    }
}
