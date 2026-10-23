package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * M3 Expressive 底部导航栏。
 *
 * <p>相比 {@code BottomNavigationView}，这里用自绘的「选中底板」取代固定尺寸的 active
 * indicator，从而支持三项 Expressive 行为：
 * <ul>
 *   <li><b>底板包裹图标 + 文字</b>：底板高度跟随整条底栏，而不是只罩住 24dp 图标；</li>
 *   <li><b>下拉拖动</b>：横向拖动时底板实时跟随手指移动，越过相邻标签即切换预览，
 *       松手后吸附到最近的标签（Expressive 的「可拖动指示器」）；</li>
 *   <li><b>描边 / 填充双态图标</b>：选中项用 Filled 字形，未选中用 Outlined 字形，
 *       两层图标做交叉淡入淡出，颜色在 onSecondaryContainer / onSurfaceVariant 间切换。</li>
 * </ul>
 *
 * <p>无障碍：每个标签是一个可点击的 Tab 节点，暴露 {@code android.widget.Tab} 语义与
 * 选中状态；底板本身不参与焦点遍历。
 */
public class ExpressiveNavBar extends ViewGroup {

    /** 选中回调；返回 true 表示事件已消费。 */
    public interface OnItemSelectedListener {
        boolean onItemSelected(int itemId);
    }

    /** 单个标签的声明：id（沿用菜单 id）+ 描边图标 + 填充图标 + 文案。 */
    public static final class Item {
        final int id;
        @DrawableRes
        final int iconOutline;
        @DrawableRes
        final int iconFilled;
        @StringRes
        final int label;

        public Item(int id, @DrawableRes int iconOutline,
                    @DrawableRes int iconFilled, @StringRes int label) {
            this.id = id;
            this.iconOutline = iconOutline;
            this.iconFilled = iconFilled;
            this.label = label;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<ItemView> itemViews = new ArrayList<>();

    /** 选中底板：一个圆角矩形，位置与宽度随标签切换 / 拖动而变化 */
    private final View indicator;

    private final int insetH;
    private final int insetV;
    private final int iconSize;
    private final int labelGap;
    private final int touchSlop;

    private final int activeTint;
    private final int inactiveTint;

    private OnItemSelectedListener listener;
    private int selectedIndex = -1;
    /** 拖动过程中的临时选中项（>=0 时优先于 selectedIndex 显示） */
    private int previewIndex = -1;

    private float downX;
    private boolean dragging;

    public ExpressiveNavBar(Context context) {
        this(context, null);
    }

    public ExpressiveNavBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        insetH = Math.round(6f * density);
        insetV = Math.round(4f * density);
        iconSize = Math.round(24f * density);
        labelGap = Math.round(2f * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        activeTint = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSecondaryContainer);
        inactiveTint = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        int pillColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSecondaryContainer);

        indicator = new View(context);
        MaterialShapeDrawable background = new MaterialShapeDrawable();
        background.setFillColor(ColorStateList.valueOf(pillColor));
        indicator.setBackground(background);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(indicator);

        setClipChildren(true);
        setClipToPadding(false);
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    public void setItems(@NonNull List<Item> newItems) {
        for (ItemView view : itemViews) {
            removeView(view);
        }
        itemViews.clear();
        items.clear();

        for (Item item : newItems) {
            ItemView view = new ItemView(getContext(), item);
            view.setOnClickListener(v -> {
                int index = itemViews.indexOf(v);
                if (index >= 0) {
                    select(index, true);
                }
            });
            addView(view);
            itemViews.add(view);
            items.add(item);
        }
        if (selectedIndex >= items.size()) {
            selectedIndex = -1;
        }
        requestLayout();
    }

    public void setOnItemSelectedListener(@Nullable OnItemSelectedListener listener) {
        this.listener = listener;
    }

    /** 外部切换选中项（不触发监听，避免与调用方的目的地切换互相递归）。 */
    public void setSelectedItemId(int itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == itemId) {
                if (i != selectedIndex) {
                    select(i, false);
                } else {
                    previewIndex = -1;
                    applyItemStates(true);
                }
                return;
            }
        }
    }

    public int getSelectedItemId() {
        return selectedIndex >= 0 && selectedIndex < items.size()
                ? items.get(selectedIndex).id : 0;
    }

    // ------------------------------------------------------------------
    // 选中 / 底板动画
    // ------------------------------------------------------------------

    private void select(int index, boolean notify) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        boolean changed = index != selectedIndex;
        selectedIndex = index;
        previewIndex = -1;
        applyItemStates(true);
        animateIndicatorTo(index, true);
        if (notify && changed && listener != null) {
            listener.onItemSelected(items.get(index).id);
        }
    }

    private int activeIndex() {
        return previewIndex >= 0 ? previewIndex : selectedIndex;
    }

    private void applyItemStates(boolean animate) {
        boolean animated = animate && MotionManager.animationsEnabled(getContext());
        long duration = Math.min(160L, MotionManager.controlDuration(getContext()));
        int active = activeIndex();
        for (int i = 0; i < itemViews.size(); i++) {
            ItemView item = itemViews.get(i);
            boolean on = i == active;
            item.setSelected(on);
            int tint = on ? activeTint : inactiveTint;
            ImageViewCompat.setImageTintList(item.outline, ColorStateList.valueOf(tint));
            ImageViewCompat.setImageTintList(item.filled, ColorStateList.valueOf(tint));
            item.label.setTextColor(tint);
            item.label.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);

            float outlineAlpha = on ? 0f : 1f;
            float filledAlpha = on ? 1f : 0f;
            if (!animated) {
                item.outline.setAlpha(outlineAlpha);
                item.filled.setAlpha(filledAlpha);
                continue;
            }
            item.outline.animate().alpha(outlineAlpha).setDuration(duration)
                    .setInterpolator(MotionManager.standard(getContext())).start();
            item.filled.animate().alpha(filledAlpha).setDuration(duration)
                    .setInterpolator(MotionManager.standard(getContext())).start();
        }
    }

    private void animateIndicatorTo(int index, boolean animate) {
        if (index < 0 || indicator.getWidth() == 0) {
            return;
        }
        float target = indicatorLeftFor(index);
        indicator.animate().cancel();
        if (!animate || !MotionManager.animationsEnabled(getContext())) {
            indicator.setTranslationX(target - indicator.getLeft());
            return;
        }
        indicator.animate()
                .translationX(target - indicator.getLeft())
                .setDuration(MotionManager.controlDuration(getContext()))
                .setInterpolator(MotionManager.emphasized(getContext()))
                .start();
    }

    // ------------------------------------------------------------------
    // 测量 / 布局
    // ------------------------------------------------------------------

    private int cellWidth() {
        if (itemViews.isEmpty()) {
            return 0;
        }
        return (getWidth() - getPaddingLeft() - getPaddingRight()) / itemViews.size();
    }

    /** 标签 i 在该方向下的可视列序号（RTL 时镜像）。 */
    private int visualIndex(int index) {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? itemViews.size() - 1 - index : index;
    }

    private float cellCenterX(int index) {
        int cell = cellWidth();
        return getPaddingLeft() + visualIndex(index) * cell + cell / 2f;
    }

    private float indicatorLeftFor(int index) {
        return cellCenterX(index) - indicator.getWidth() / 2f;
    }

    private float minIndicatorLeft() {
        return getPaddingLeft() + insetH;
    }

    private float maxIndicatorLeft() {
        return getWidth() - getPaddingRight() - insetH - indicator.getWidth();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : Math.max(getSuggestedMinimumHeight(), Math.round(56f * density));
        int cell = itemViews.isEmpty() ? 0
                : Math.max(0, (width - getPaddingLeft() - getPaddingRight()) / itemViews.size());
        int itemHeight = height - getPaddingTop() - getPaddingBottom();
        int childWidth = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(itemHeight, MeasureSpec.EXACTLY);
        for (ItemView view : itemViews) {
            view.measure(childWidth, childHeight);
        }
        indicator.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, cell - 2 * insetH), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, itemHeight - 2 * insetV), MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = itemViews.size();
        if (count == 0) {
            return;
        }
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int width = getWidth() - left - getPaddingRight();
        int height = getHeight() - top - getPaddingBottom();
        int cell = width / count;

        for (int i = 0; i < count; i++) {
            int visual = visualIndex(i);
            int childLeft = left + visual * cell;
            int childRight = visual == count - 1 ? left + width : childLeft + cell;
            itemViews.get(i).layout(childLeft, top, childRight, top + height);
        }

        int pillWidth = Math.max(0, cell - 2 * insetH);
        int pillHeight = Math.max(0, height - 2 * insetV);
        indicator.layout(0, top + insetV, pillWidth, top + insetV + pillHeight);
        MaterialShapeDrawable background = (MaterialShapeDrawable) indicator.getBackground();
        // Expressive 的选中底板跟随底栏胶囊的圆角（28dp）：高度不足时退化为半高胶囊，
        // 这样底板边缘与外壳内缘的弧度一致，不会出现「外圆内方」的错位感
        float radius = Math.min(pillHeight / 2f,
                Math.round(28f * getResources().getDisplayMetrics().density));
        background.setShapeAppearanceModel(background.getShapeAppearanceModel().toBuilder()
                .setAllCornerSizes(radius)
                .build());
        // 拖动过程中禁止按「最近标签」重排底板：切换预览项会让文字变粗并触发一次 layout，
        // 若这里再吸附到标签中心，就会出现「闪到 tab 正中又跳回手指位置」的抖动
        if (!dragging) {
            indicator.setTranslationX(indicatorLeftFor(Math.max(0, activeIndex())) - indicator.getLeft());
        }
    }

    @Override
    public int getSuggestedMinimumHeight() {
        return getLayoutParams() != null && getLayoutParams().height > 0
                ? getLayoutParams().height : super.getSuggestedMinimumHeight();
    }

    // ------------------------------------------------------------------
    // 拖动
    // ------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (itemViews.isEmpty()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(event.getX() - downX) > touchSlop) {
                    dragging = true;
                    indicator.animate().cancel();
                    dragTo(event.getX());
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    dragTo(event.getX());
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    select(nearestIndex(event.getX()), true);
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    // 手势被打断：底板回到原选中项，不做切换
                    dragging = false;
                    previewIndex = -1;
                    applyItemStates(true);
                    animateIndicatorTo(selectedIndex, true);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void dragTo(float x) {
        if (indicator.getWidth() == 0) {
            return;
        }
        float target = x - indicator.getWidth() / 2f;
        indicator.setTranslationX(Math.max(minIndicatorLeft(),
                Math.min(maxIndicatorLeft(), target)) - indicator.getLeft());

        int index = nearestIndex(x);
        if (index != previewIndex) {
            previewIndex = index;
            applyItemStates(true);
            HapticsManager.swipe(this);
        }
    }

    private int nearestIndex(float x) {
        if (itemViews.isEmpty()) {
            return -1;
        }
        int cell = cellWidth();
        if (cell <= 0) {
            return 0;
        }
        int index = (int) ((x - getPaddingLeft()) / cell);
        index = Math.max(0, Math.min(itemViews.size() - 1, index));
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? itemViews.size() - 1 - index : index;
    }

    // ------------------------------------------------------------------
    // 单个标签
    // ------------------------------------------------------------------

    private final class ItemView extends LinearLayout {

        final ImageView outline;
        final ImageView filled;
        final TextView label;

        ItemView(Context context, Item item) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);
            setContentDescription(context.getString(item.label));
            int padding = Math.round(4f * getResources().getDisplayMetrics().density);
            setPadding(0, padding, 0, padding);

            TypedValue ripple = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId);
            }

            FrameLayout iconBox = new FrameLayout(context);
            outline = new ImageView(context);
            outline.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            outline.setImageResource(item.iconOutline);
            ImageViewCompat.setImageTintList(outline, ColorStateList.valueOf(inactiveTint));
            filled = new ImageView(context);
            filled.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            filled.setImageResource(item.iconFilled);
            filled.setAlpha(0f);
            ImageViewCompat.setImageTintList(filled, ColorStateList.valueOf(inactiveTint));
            iconBox.addView(outline,
                    new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            iconBox.addView(filled,
                    new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            addView(iconBox, new LayoutParams(iconSize, iconSize));

            label = new TextView(context);
            TextViewCompat.setTextAppearance(label,
                    com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText(item.label);
            label.setTextColor(inactiveTint);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = labelGap;
            addView(label, lp);

            ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                              @NonNull AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Tab");
                    info.setSelected(isSelected());
                }
            });
        }
    }
}
