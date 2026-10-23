package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

/**
 * Expressive 选择面板：浮在点击位置附近的小卡片（覆盖当前设置项上层）。
 *
 * <p>替代此前的 {@code AlertDialog + setSingleChoiceItems}：
 * <ul>
 *   <li>紧凑尺寸：14sp 文案、44dp 行高，整体比常规对话框小一圈；</li>
 *   <li>选中项用 tertiary 容器色填充并在行首显示对勾；</li>
 *   <li>出现在手指附近，从左上角轻微放大现身（Overshoot 回弹），点选即应用并收起。</li>
 * </ul>
 *
 * <p>面板在控件下方空间不足时自动翻到上方，并始终限制在窗口可见区域内。
 */
final class ExpressiveMenu {

    interface OnPick {
        void onPick(int index);
    }

    private static final int MAX_WIDTH_DP = 260;
    private static final int MIN_WIDTH_DP = 140;
    /**
     * 卡片外的透明留白。
     *
     * <p>回弹动画会短暂放大到 100% 以上，窗口边界是按内容尺寸裁切的；
     * 留一圈透明边距可以避免圆角在过冲那一帧被切平。
     */
    private static final int WRAPPER_PADDING_DP = 12;

    private ExpressiveMenu() {
    }

    /**
     * 以 {@code anchor} 为基准弹出选择面板。
     *
     * @param anchor      基准控件（通常是设置行）
     * @param items       选项文案
     * @param checked     当前选中下标，越界表示未选中
     */
    static void show(@Nullable View anchor, CharSequence[] items, int checked, OnPick onPick) {
        present(anchor, items, checked, onPick, false);
    }

    /** 没有可用的贴靠控件时居中显示（保证选项完整可见，而不是贴到屏幕边缘被裁掉）。 */
    static void showCentered(@Nullable View root, CharSequence[] items, int checked,
                             OnPick onPick) {
        present(root, items, checked, onPick, true);
    }

    private static void present(@Nullable View anchor, CharSequence[] items, int checked,
                                OnPick onPick, boolean centered) {
        if (anchor == null || items == null || items.length == 0) {
            return;
        }
        Context context = anchor.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int checkedIndex = (checked >= 0 && checked < items.length) ? checked : -1;

        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(Math.round(18f * density));
        card.setCardElevation(6f * density);
        card.setCardBackgroundColor(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        card.setStrokeWidth(0);
        card.setClipToOutline(true);
        card.setClipChildren(true);
        card.setUseCompatPadding(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        int innerPad = Math.round(6f * density);
        column.setPadding(innerPad, innerPad, innerPad, innerPad);
        card.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 透明外圈：只为回弹动画留出绘制余量，不参与视觉
        FrameLayout wrapper = new FrameLayout(context);
        int wrapperPad = Math.round(WRAPPER_PADDING_DP * density);
        wrapper.setPadding(wrapperPad, wrapperPad, wrapperPad, wrapperPad);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        wrapper.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PopupWindow popup = new PopupWindow(wrapper,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.setElevation(6f * density);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.transition.Fade exit = new android.transition.Fade();
            exit.setDuration(90L);
            popup.setExitTransition(exit);
        }

        int rowRadius = Math.round(14f * density);
        int selectedBg = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorTertiaryContainer);
        int selectedFg = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOnTertiaryContainer);
        int normalFg = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOnSurface);
        int rippleColor = (MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOnSurface) & 0x00FFFFFF) | 0x1F000000;

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            final boolean selected = i == checkedIndex;

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(10f * density), 0, Math.round(12f * density), 0);
            row.setMinimumHeight(Math.round(44f * density));
            row.setBackground(selected ? pill(selectedBg, rowRadius)
                    : ripple(rowRadius, rippleColor));

            ImageView check = new ImageView(context);
            int icon = Math.round(18f * density);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(icon, icon);
            checkLp.setMarginEnd(Math.round(6f * density));
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(ColorStateList.valueOf(selected ? selectedFg : Color.TRANSPARENT));
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(check, checkLp);

            TextView label = new TextView(context);
            label.setText(items[i]);
            label.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyMedium);
            label.setTextColor(selected ? selectedFg : normalFg);
            if (selected) {
                label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            }
            label.setMaxLines(2);
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                HapticsManager.tap(v);
                popup.dismiss();
                if (onPick != null) {
                    onPick.onPick(index);
                }
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowLp.topMargin = Math.round(1f * density);
            }
            column.addView(row, rowLp);
        }

        // 卡片宽度 = 内容自然宽（选项少时保持紧凑），限制在 [140dp, 260dp]；
        // 显式定宽后再测外圈，避免卡片被最小宽撑开后行背景与卡片对不齐
        column.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxCardWidth = Math.min(
                Math.round(MAX_WIDTH_DP * density),
                context.getResources().getDisplayMetrics().widthPixels
                        - Math.round(48f * density));
        final int cardWidth = Math.min(maxCardWidth,
                Math.max(column.getMeasuredWidth() + innerPad * 2,
                        Math.round(MIN_WIDTH_DP * density)));
        if (card.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams cardLp = (FrameLayout.LayoutParams) card.getLayoutParams();
            cardLp.width = cardWidth;
            cardLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            card.setLayoutParams(cardLp);
        }
        wrapper.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int totalWidth = wrapper.getMeasuredWidth();
        final int totalHeight = wrapper.getMeasuredHeight();
        popup.setWidth(totalWidth);
        popup.setHeight(totalHeight);

        if (!anchor.isAttachedToWindow()) {
            return;
        }

        // 从卡片左上角轻微放大现身：面板贴着手指，从点击处展开最自然
        card.setPivotX(0f);
        card.setPivotY(0f);
        card.setScaleX(0.86f);
        card.setScaleY(0.86f);
        card.setAlpha(0f);

        if (centered) {
            popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);
            startEnterAnimation(card);
            return;
        }

        // 位置统一用屏幕坐标计算（与 showAtLocation 一致），并把结果钳制在可见区域内，
        // 保证面板不会跑到屏幕外被裁成「只剩一个选项」。
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        int margin = Math.round(8f * density);

        // 面板直接出现在「手指位置」附近（如图例：覆盖在当前设置项上层），
        // 让出 8dp 避免指尖压住第一个选项；拿不到触点时退回该行左上角。
        float[] touch = Md3ListItemView.lastTouchPoint();
        boolean touchInside = touch != null
                && touch[0] >= location[0] - margin
                && touch[0] <= location[0] + anchor.getWidth() + margin
                && touch[1] >= location[1] - margin
                && touch[1] <= location[1] + anchor.getHeight() + margin;
        int desiredLeft;
        int desiredTop;
        if (touchInside) {
            desiredLeft = Math.round(touch[0]) - margin;
            desiredTop = Math.round(touch[1]) + margin;
        } else {
            desiredLeft = location[0] + anchor.getPaddingLeft();
            desiredTop = location[1] + Math.round(anchor.getHeight() / 2f);
        }
        int x = desiredLeft - wrapperPad;
        int y = desiredTop - wrapperPad;
        x = Math.max(visible.left + margin,
                Math.min(x, visible.right - totalWidth - margin));
        y = Math.max(visible.top + margin,
                Math.min(y, visible.bottom - totalHeight - margin));
        // showAtLocation 的坐标相对父窗口；用根视图的屏幕位置换算，兼容非全屏（edge-to-edge）窗口
        View root = anchor.getRootView();
        if (root != null) {
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            x -= rootLocation[0];
            y -= rootLocation[1];
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        startEnterAnimation(card);
    }

    /**
     * 现身动画：从箭头方向轻微放大并淡入。
     *
     * <p>Overshoot 会先略微超过 100% 再回落，形成一次很轻的回弹；
     * 位移幅度小，不会让人觉得花哨。
     */
    private static void startEnterAnimation(View card) {
        card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260L)
                .setInterpolator(new OvershootInterpolator(2.4f))
                .start();
    }

    private static Drawable pill(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /** 未选中行：透明底 + 按胶囊轮廓裁剪的涟漪（否则矩形水波纹会溢出圆角）。 */
    private static Drawable ripple(int radius, int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }
}
