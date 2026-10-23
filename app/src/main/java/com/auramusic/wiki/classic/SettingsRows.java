package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * 设置页「可选值行」统一构造器（需求 1）。
 *
 * <p>把原先平铺的单选组（RadioGroup + MaterialRadioButton）统一改造为
 * 「一行显示当前取值 + 点击唤起 Expressive 选择面板」的交互形式：
 * <ul>
 *   <li>行本身使用 MD3 的 {@code selectableItemBackground} 与 ≥48dp 热区；</li>
 *   <li>子菜单为浮在点击位置附近的圆角卡片（{@link ExpressiveMenu}），
 *       选中项以 tertiary 容器胶囊 + 对勾强调；</li>
 *   <li>行与行之间不再使用分隔线，改由行高与内边距形成留白层级
 *       （与参考应用的「无分割线」列表观感一致）。</li>
 * </ul>
 */
final class SettingsRows {

    /** 当前取值的惰性提供者，便于外部状态变化后刷新该行。 */
    interface ValueProvider {
        CharSequence value();
    }

    /** 子菜单选项回调。 */
    interface ChoiceHandler {
        void onChoice(int index);
    }

    /**
     * 最近一次被点击的可选值行。
     *
     * <p>选择面板要贴在对应的设置项旁，但现有调用方（{@code this::pickXxx}）拿不到行对象，
     * 因此由行自身记录一份弱引用，{@link #showSingleChoice} 直接取用。
     */
    private static java.lang.ref.WeakReference<View> choiceAnchor =
            new java.lang.ref.WeakReference<>(null);

    private SettingsRows() {
    }

    /**
     * 追加一行可选值设置项。
     *
     * @param descRes       详细说明文案，0 表示不显示说明
     * @param valueProvider 右侧「当前取值」文案
     * @param onClick       点击行时的动作（通常是弹出单选子菜单）
     * @return 行视图，可交给 {@link #refreshValue} 后续刷新
     */
    static View addOptionRow(LayoutInflater inflater, LinearLayout container,
                             @StringRes int titleRes, @StringRes int descRes,
                             ValueProvider valueProvider, Runnable onClick) {
        return addOptionRow(inflater, container, titleRes, descRes, valueProvider, onClick,
                0, Md3ListItemView.TILE_NONE, false);
    }

    /** 带前导图标（彩色底托）的可选值行。 */
    static View addOptionRow(LayoutInflater inflater, LinearLayout container,
                             @StringRes int titleRes, @StringRes int descRes,
                             ValueProvider valueProvider, Runnable onClick,
                             @DrawableRes int iconRes, int iconTone) {
        return addOptionRow(inflater, container, titleRes, descRes, valueProvider, onClick,
                iconRes, iconTone, false);
    }

    /**
     * 完整版可选值行。
     *
     * @param asCards 为 true 时把该行包进一张独立圆角卡片（Expressive 分隔样式）
     */
    static View addOptionRow(LayoutInflater inflater, LinearLayout container,
                             @StringRes int titleRes, @StringRes int descRes,
                             ValueProvider valueProvider, Runnable onClick,
                             @DrawableRes int iconRes, int iconTone, boolean asCards) {
        if (container == null) {
            return null;
        }
        final Context context = container.getContext();
        Md3ListItemView row = new Md3ListItemView(context);
        row.setTitle(context.getString(titleRes));
        if (descRes != 0) {
            row.setSupporting(context.getString(descRes));
        }
        if (iconRes != 0) {
            row.setLeadingIcon(iconRes, iconTone);
        }
        row.setTrailingMode(Md3ListItemView.TRAILING_VALUE);
        if (valueProvider != null) {
            row.setTrailingValue(valueProvider.value());
        }
        final Runnable action = onClick;
        row.setOnClickListener(v -> {
            HapticsManager.tap(v);
            // 记住被点的行：随后的选择面板以它为基准贴靠弹出
            choiceAnchor = new java.lang.ref.WeakReference<>(v);
            if (action != null) {
                action.run();
            }
        });
        attachRow(container, row, asCards);
        return row;
    }

    /** 刷新某一行右侧的「当前取值」文案。 */
    static void refreshValue(@Nullable View row, ValueProvider valueProvider) {
        if (!(row instanceof Md3ListItemView) || valueProvider == null) {
            return;
        }
        ((Md3ListItemView) row).setTrailingValue(valueProvider.value());
    }

    /**
     * 追加一行开关设置项（同样带标题、详细说明与行间分隔线）。
     *
     * @return 行内的 {@link MaterialSwitch}，便于外部联动（如开关关闭时置灰关联项）
     */
    static MaterialSwitch addSwitchRow(LayoutInflater inflater, LinearLayout container,
                                       @StringRes int titleRes, @StringRes int descRes,
                                       boolean checked,
                                       @Nullable CompoundButton.OnCheckedChangeListener listener) {
        return addSwitchRow(inflater, container, titleRes, descRes, checked, listener,
                0, Md3ListItemView.TILE_NONE, false);
    }

    /** 带前导图标（彩色底托）的开关行。 */
    static MaterialSwitch addSwitchRow(LayoutInflater inflater, LinearLayout container,
                                       @StringRes int titleRes, @StringRes int descRes,
                                       boolean checked,
                                       @Nullable CompoundButton.OnCheckedChangeListener listener,
                                       @DrawableRes int iconRes, int iconTone) {
        return addSwitchRow(inflater, container, titleRes, descRes, checked, listener,
                iconRes, iconTone, false);
    }

    /** 完整版开关行；{@code asCards} 为 true 时每行独立成卡。 */
    static MaterialSwitch addSwitchRow(LayoutInflater inflater, LinearLayout container,
                                       @StringRes int titleRes, @StringRes int descRes,
                                       boolean checked,
                                       @Nullable CompoundButton.OnCheckedChangeListener listener,
                                       @DrawableRes int iconRes, int iconTone, boolean asCards) {
        if (container == null) {
            return null;
        }
        final Context context = container.getContext();
        Md3ListItemView row = new Md3ListItemView(context);
        row.setTitle(context.getString(titleRes));
        if (descRes != 0) {
            row.setSupporting(context.getString(descRes));
        }
        if (iconRes != 0) {
            row.setLeadingIcon(iconRes, iconTone);
        }
        MaterialSwitch button = row.setTrailingSwitch();
        button.setChecked(checked);
        if (listener != null) {
            button.setOnCheckedChangeListener(listener);
        }
        // 整行可点：与参考应用一致，点文字区域也能切换
        row.setOnClickListener(v -> button.toggle());
        attachRow(container, row, asCards);
        return button;
    }

    /**
     * 把行挂到容器：默认直接加入；{@code asCards} 时外包一张 8dp 圆角的独立卡片，
     * 行与行之间留 4dp 间隙，形成参考应用那种「每项一张卡」的分隔观感。
     */
    private static void attachRow(LinearLayout container, View row, boolean asCards) {
        if (!asCards) {
            container.addView(row);
            return;
        }
        Context context = container.getContext();
        MaterialCardView card = createRowCard(context);
        int inset = context.getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        row.setPadding(inset, 0, inset, 0);
        card.addView(row);
        container.addView(card);
    }

    /**
     * 创建一张包裹单个设置行的独立卡片。
     *
     * <p>圆角 8dp、卡间距 4dp、无海拔无描边，并开启轮廓裁剪 ——
     * 行内的 {@code selectableItemBackground} 是矩形，不裁剪会溢出四个角。
     */
    private static MaterialCardView createRowCard(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        int radius = Math.round(8f * context.getResources().getDisplayMetrics().density);
        int gap = Math.round(4f * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        // 卡片与页面里其他卡同宽：不再额外内缩，避免出现「比分组标题窄一截」的异常宽度
        lp.bottomMargin = gap;
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        card.setRadius(radius);
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        // 行内的水波纹是矩形，必须按卡片圆角裁剪，否则按下时会溢出四个角
        card.setClipToOutline(true);
        card.setClipChildren(true);
        return card;
    }

    /**
     * 按「设置项样式」偏好重排 XML 里静态声明的分组。
     *
     * <ul>
     *   <li><b>整卡分组</b>：与 XML 默认结构一致，不做任何改动（行之间保留 MaterialDivider）；</li>
     *   <li><b>独立卡片</b>：隐藏分隔线，把每个 {@link Md3ListItemView} 包进一张独立圆角卡，
     *       同时把外层分组卡改成透明 + 直角 —— 否则外层卡的 24dp 圆角会裁掉
     *       首/末行卡片自己的圆角。</li>
     * </ul>
     *
     * <p>调用时机：页面 onCreate 中所有行（含运行时添加的行）就绪之后。
     * 非行子视图（说明文字等）保持原位，不参与包裹。
     */
    static void applyRowStyle(@Nullable LinearLayout container) {
        if (container == null || container.getChildCount() == 0) {
            return;
        }
        // 两种样式都不使用分割线：整卡分组靠行的留白分层，独立卡片靠卡间距分层。
        // 之前这里只在「独立卡片」分支隐藏分割线，切回整卡分组后旧的 MaterialDivider
        // 会重新露出来，和「无分割线」的统一标准不一致。
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof MaterialDivider) {
                child.setVisibility(View.GONE);
            }
        }
        if (!AppearanceManager.rowsAsCards(container.getContext())) {
            // 关闭独立卡片后，行的左右起点要与独立卡片模式保持一致（20dp）。
            // 注意留白只能由行自己承担一份：XML 行本来就带 20dp，容器若再加 20dp，
            // 整卡分组下的图标/文字/箭头都会比独立卡片模式多缩进一倍，看起来明显偏右。
            Context context = container.getContext();
            int inset = context.getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
            container.setPadding(0, container.getPaddingTop(), 0, container.getPaddingBottom());
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof Md3ListItemView || child instanceof TextView) {
                    child.setPadding(inset, child.getPaddingTop(), inset,
                            child.getPaddingBottom());
                }
            }
            if (container.getParent() instanceof MaterialCardView) {
                MaterialCardView host = (MaterialCardView) container.getParent();
                host.setCardBackgroundColor(MaterialColors.getColor(host,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
                host.setStrokeWidth(0);
                host.setRadius(context.getResources().getDimension(R.dimen.shape_card));
            }
            // 水波纹按分组卡圆角裁剪：首行只圆上角、末行只圆下角，避免矩形水波纹
            // 溢出分组卡的四个圆角（只要 top/bottom 两处是直角就会看到方形）。
            applyGroupedRowRipples(container);
            return;
        }
        Context context = container.getContext();
        int inset = context.getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        // 倒序处理：移除 / 插入同一位置时下标仍然有效
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child instanceof MaterialDivider) {
                child.setVisibility(View.GONE);
            } else if (child instanceof Md3ListItemView) {
                container.removeViewAt(i);
                MaterialCardView card = createRowCard(context);
                Md3ListItemView row = (Md3ListItemView) child;
                // 行原本挂在 LinearLayout 里，带的是 LinearLayout.LayoutParams；
                // 直接塞进 MaterialCardView（FrameLayout 体系）会在测量时类型转换崩溃
                row.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT));
                row.setPadding(inset, 0, inset, 0);
                card.addView(row);
                container.addView(card, i);
            }
        }
        // 外层分组卡让位：透明 + 直角，卡片自身的左右内边距也移交给了行卡
        if (container.getParent() instanceof MaterialCardView) {
            MaterialCardView host = (MaterialCardView) container.getParent();
            host.setCardBackgroundColor(Color.TRANSPARENT);
            host.setStrokeWidth(0);
            host.setRadius(0f);
        }
        container.setPadding(0, container.getPaddingTop(), 0, container.getPaddingBottom());
        // 分组里的说明文字不是行，不会被包卡；容器取消左右内边距后要自己补回来，
        // 否则会与行卡内的文字列对不齐
        int textInsetV = Math.round(8f * context.getResources().getDisplayMetrics().density);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof TextView) {
                child.setPadding(inset, textInsetV, inset, textInsetV);
            }
        }
        applyGroupedCorners(container);
    }

    /**
     * 独立卡片组的圆角：首卡的上两角、末卡的下两角用大圆角（与页面其他卡一致），
     * 其余相邻缝隙处的角用小圆角，形成「整组一张卡被切开」的观感。
     *
     * @param container 由 {@link #attachRow} 以卡片形式填充的容器
     */
    static void applyGroupedCorners(LinearLayout container) {
        if (container == null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) instanceof MaterialCardView) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        float outer = container.getResources().getDimensionPixelSize(R.dimen.shape_card);
        float inner = container.getResources().getDimensionPixelSize(R.dimen.shape_sm);
        int index = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof MaterialCardView)) {
                continue;
            }
            boolean first = index == 0;
            boolean last = index == count - 1;
            ShapeAppearanceModel model = new ShapeAppearanceModel.Builder()
                    .setTopLeftCornerSize(first ? outer : inner)
                    .setTopRightCornerSize(first ? outer : inner)
                    .setBottomLeftCornerSize(last ? outer : inner)
                    .setBottomRightCornerSize(last ? outer : inner)
                    .build();
            ((MaterialCardView) child).setShapeAppearanceModel(model);
            index++;
        }
        // 行内水波纹同步使用与卡片一致的四角半径（首卡上角大、末卡下角大），
        // 这样即使子视图绘制不受轮廓裁剪，按下也不会露出矩形边角。
        applyCardRowRipples(container);
    }

    /** 独立卡片模式：让每张卡里的行使用与卡片相同的圆角涟漪。 */
    private static void applyCardRowRipples(LinearLayout container) {
        int count = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) instanceof MaterialCardView) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        float outer = container.getResources().getDimensionPixelSize(R.dimen.shape_card);
        float inner = container.getResources().getDimensionPixelSize(R.dimen.shape_sm);
        int index = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof MaterialCardView)) {
                continue;
            }
            MaterialCardView card = (MaterialCardView) child;
            View row = card.getChildCount() > 0 ? card.getChildAt(0) : null;
            float top = index == 0 ? outer : inner;
            float bottom = index == count - 1 ? outer : inner;
            applyRoundedRipple(row, top, top, bottom, bottom);
            index++;
        }
    }

    /** 整卡分组模式：首行圆上角、末行圆下角，中间行保持直角。 */
    private static void applyGroupedRowRipples(LinearLayout container) {
        int count = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            if (container.getChildAt(i) instanceof Md3ListItemView) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        float outer = container.getResources().getDimensionPixelSize(R.dimen.shape_card);
        int index = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof Md3ListItemView)) {
                continue;
            }
            float top = index == 0 ? outer : 0f;
            float bottom = index == count - 1 ? outer : 0f;
            applyRoundedRipple(child, top, top, bottom, bottom);
            index++;
        }
    }

    /**
     * 给行换上「按指定四角半径裁剪」的涟漪背景。
     *
     * @param topLeft     左上角半径（px）
     * @param topRight    右上角半径（px）
     * @param bottomRight 右下角半径（px）
     * @param bottomLeft  左下角半径（px）
     */
    private static void applyRoundedRipple(View row, float topLeft, float topRight,
                                           float bottomRight, float bottomLeft) {
        if (row == null) {
            return;
        }
        int color = MaterialColors.getColor(row,
                androidx.appcompat.R.attr.colorControlHighlight);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadii(new float[]{topLeft, topLeft, topRight, topRight,
                bottomRight, bottomRight, bottomLeft, bottomLeft});
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(color), null, mask));
    }

    /** 启用 / 置灰一行可选值设置项（用于「振动强度」这类存在前置依赖的项）。 */
    static void setRowEnabled(@Nullable View row, boolean enabled) {
        if (row == null) {
            return;
        }
        row.setEnabled(enabled);
        row.setClickable(enabled);
        row.setAlpha(enabled ? 1f : 0.5f);
    }

    /**
     * 弹出 Expressive 选择面板（贴靠在最近点击的设置项旁）。
     *
     * @param items        选项文案
     * @param checkedIndex 当前选中项下标，越界时视为未选中
     */
    static void showSingleChoice(Context context, @StringRes int titleRes, CharSequence[] items,
                                 int checkedIndex, ChoiceHandler handler) {
        if (context == null || items == null || items.length == 0) {
            return;
        }
        View anchor = choiceAnchor.get();
        if (anchor == null || !anchor.isAttachedToWindow()) {
            anchor = Md3ListItemView.lastClickedRow();
        }
        if (anchor != null && anchor.isAttachedToWindow()) {
            showSingleChoice(anchor, items, checkedIndex, handler);
            return;
        }
        // 兜底：没有可贴靠的行（例如旋转后立即弹出）时居中显示，
        // 避免面板贴到屏幕边缘被裁成「只剩一个选项」
        View content = context instanceof android.app.Activity
                ? ((android.app.Activity) context).findViewById(android.R.id.content)
                : null;
        if (content == null) {
            return;
        }
        int checked = (checkedIndex >= 0 && checkedIndex < items.length) ? checkedIndex : -1;
        ExpressiveMenu.showCentered(content, items, checked, which -> {
            if (handler != null) {
                handler.onChoice(which);
            }
        });
    }

    /** 以指定控件为基准弹出 Expressive 选择面板。 */
    static void showSingleChoice(@Nullable View anchor, CharSequence[] items,
                                 int checkedIndex, ChoiceHandler handler) {
        if (anchor == null || items == null || items.length == 0) {
            return;
        }
        int checked = (checkedIndex >= 0 && checkedIndex < items.length) ? checkedIndex : -1;
        ExpressiveMenu.show(anchor, items, checked, which -> {
            if (handler != null) {
                handler.onChoice(which);
            }
        });
    }

    /** 区块之间的分级标题（比卡片内标题低一级）。 */
    static void addSectionLabel(LayoutInflater inflater, LinearLayout container, @StringRes int titleRes) {
        if (inflater == null || container == null) {
            return;
        }
        TextView tv = new TextView(inflater.getContext());
        tv.setText(titleRes);
        tv.setTextAppearance(R.style.TextAppearance_ClassicWiki_SectionTitle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = container.getResources().getDimensionPixelSize(R.dimen.spacing_md);
        lp.bottomMargin = container.getResources().getDimensionPixelSize(R.dimen.spacing_sm);
        tv.setLayoutParams(lp);
        container.addView(tv);
    }
}
