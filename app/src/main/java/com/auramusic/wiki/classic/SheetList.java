package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * 选择型 / 浏览型列表的 M3 底部抽屉（Stage 19）。
 *
 * <p>在此之前页码、音轨号、未读消息都用 {@code AlertDialog} 的 items 展示：
 * 长列表会被对话框标题挤占高度，且与已经 BottomSheet 化的艺人选择器风格不一致。
 * 这里统一成底部抽屉 —— 对话框只留给确认 / 破坏性操作。
 */
final class SheetList {

    interface OnPick {
        void onPick(int index);
    }

    /** 单选列表：带当前选中项，点选即回调并关闭。 */
    static void singleChoice(Context context, CharSequence title, CharSequence[] items,
                             int checked, OnPick onPick) {
        Host host = new Host(context, title);
        // 左右留白加在列表容器上而不是单行上：行的内边距不会计入单选按钮图标的位置，
        // 之前圆点会顶到抽屉最左边，与标题的起始边线对不齐。
        host.list.setPadding(host.inset, 0, host.inset, 0);
        for (int i = 0; i < items.length; i++) {
            MaterialRadioButton row = new MaterialRadioButton(context);
            row.setText(items[i]);
            row.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setChecked(i == checked);
            row.setPadding(0, 0, 0, 0);
            final int index = i;
            row.setOnClickListener(v -> {
                host.dialog.dismiss();
                onPick.onPick(index);
            });
            host.list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, host.rowHeight));
        }
        host.show(items.length, checked);
    }

    /**
     * 普通列表：每行一个可点操作（如未读审核消息）。
     *
     * @param footer       底部动作文案，{@code null} 表示没有
     * @param footerAction 底部动作回调
     */
    static void actions(Context context, CharSequence title, CharSequence[] items,
                        @Nullable CharSequence footer, @Nullable Runnable footerAction,
                        OnPick onPick) {
        Host host = new Host(context, title);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        for (int i = 0; i < items.length; i++) {
            Md3ListItemView row = new Md3ListItemView(context);
            row.setTitle(items[i]);
            row.setTrailingMode(Md3ListItemView.TRAILING_CHEVRON);
            row.setMinimumHeight(host.rowHeight);
            row.setPadding(inset, 0, inset, 0);
            final int index = i;
            row.setOnClickListener(v -> {
                host.dialog.dismiss();
                onPick.onPick(index);
            });
            host.list.addView(row);
        }
        if (footer != null) {
            MaterialDivider divider = host.root.findViewById(R.id.sheet_footer_divider);
            MaterialButton action = host.root.findViewById(R.id.sheet_footer_action);
            divider.setVisibility(View.VISIBLE);
            action.setVisibility(View.VISIBLE);
            action.setText(footer);
            action.setOnClickListener(v -> {
                host.dialog.dismiss();
                if (footerAction != null) {
                    footerAction.run();
                }
            });
        }
        host.show(items.length);
    }

    /**
     * 空态 / 提示型抽屉。
     *
     * <p>与列表型抽屉共用同一套骨架与圆角，避免出现「有未读是底部抽屉、没有未读却弹
     * 传统 AlertDialog」的割裂感。
     */
    static void message(Context context, CharSequence title, CharSequence message) {
        Host host = new Host(context, title);
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = context.getResources().getDimensionPixelSize(R.dimen.spacing_lg);

        FrameLayout iconBox = new FrameLayout(context);
        int box = Math.round(56f * context.getResources().getDisplayMetrics().density);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(MaterialColors.getColor(block,
                com.google.android.material.R.attr.colorSecondaryContainer));
        iconBox.setBackground(circle);
        ImageView icon = new ImageView(context);
        int iconSize = Math.round(24f * context.getResources().getDisplayMetrics().density);
        icon.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        icon.setImageResource(R.drawable.ic_notifications);
        icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(iconBox,
                com.google.android.material.R.attr.colorOnSecondaryContainer)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iconBox.addView(icon);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(box, box);
        boxLp.topMargin = pad / 2;
        block.addView(iconBox, boxLp);

        TextView text = new TextView(context);
        text.setText(message);
        text.setGravity(Gravity.CENTER);
        text.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        text.setTextColor(MaterialColors.getColor(block,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = pad / 2;
        block.addView(text, textLp);

        block.setPadding(host.inset, pad, host.inset, pad);
        host.list.addView(block);
        host.showWrap();
    }

    /** 抽屉骨架：把手 + 标题 + 可滚动列表，由调用方往 {@link #list} 里塞行。 */
    private static final class Host {

        final BottomSheetDialog dialog;
        final LinearLayout root;
        final LinearLayout list;
        final NestedScrollView scroll;
        final int inset;
        final int rowHeight;

        Host(Context context, CharSequence title) {
            root = (LinearLayout) LayoutInflater.from(context)
                    .inflate(R.layout.dialog_sheet_list, null);
            ((TextView) root.findViewById(R.id.tv_sheet_title)).setText(title);
            list = root.findViewById(R.id.sheet_list);
            scroll = root.findViewById(R.id.sheet_scroll);
            inset = context.getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
            rowHeight = context.getResources().getDimensionPixelSize(R.dimen.item_height_one_line);
            dialog = new BottomSheetDialog(context);
            dialog.setContentView(root);
        }

        /** 列表最多占屏幕 6 成高，超出后在抽屉内部滚动。 */
        void show(int count) {
            show(count, -1);
        }

        /** 提示型内容：高度随内容，不按行数估算。 */
        void showWrap() {
            scroll.getLayoutParams().height = LinearLayout.LayoutParams.WRAP_CONTENT;
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            dialog.show();
        }

        /** @param scrollTo 需要滚动到可视区的行下标，{@code -1} 表示停在顶部 */
        void show(int count, int scrollTo) {
            int maxHeight = Math.round(
                    root.getResources().getDisplayMetrics().heightPixels * 0.6f);
            scroll.getLayoutParams().height = Math.min(rowHeight * count, maxHeight);
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            dialog.show();
            if (scrollTo > 0) {
                // 长列表（如 99 个音轨号）打开时把当前选中项滚到靠上位置
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, scrollTo - 2) * rowHeight));
            }
        }
    }
}
