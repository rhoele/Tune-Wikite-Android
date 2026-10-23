package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * MD3 标准列表行（单行 56dp / 双行 72dp）。
 *
 * <p>替代原先为「可选值行」「开关行」分别维护的两份布局：一份布局同时支持
 * <ul>
 *   <li>前导图标（可选，放在 40dp 圆角色块「底托」中）</li>
 *   <li>主标题 + 最多两行辅助说明</li>
 *   <li>尾部三态：取值文案 + chevron / 仅 chevron / MaterialSwitch</li>
 * </ul>
 *
 * <p>底托的分类色由 {@link #setLeadingIcon(int, int)} 指定（primary / secondary /
 * tertiary 三档），对应 MD3 的 *Container 与 On*Container 成对取色；
 * 用户在「外观设置」里关闭彩色底托后，所有行退回中性色，只保留形状层级。
 *
 * <p>水平内边距刻意不写死：放在已带 16dp 内边距的卡片里时保持 0，
 * 直接放在页面级卡片里时由 XML 的 {@code android:paddingStart/End} 指定，
 * 这样同一组件在设置首页与二级页都能对齐栅格。
 */
public class Md3ListItemView extends LinearLayout {

    /** 尾部不展示任何内容 */
    public static final int TRAILING_NONE = 0;
    /** 仅 chevron（导航行） */
    public static final int TRAILING_CHEVRON = 1;
    /** 取值文案 + chevron（可选项行） */
    public static final int TRAILING_VALUE = 2;
    /** 仅取值文案（只读信息行，如版本号） */
    public static final int TRAILING_VALUE_PLAIN = 4;
    /** MaterialSwitch（开关行） */
    public static final int TRAILING_SWITCH = 3;
    /**
     * 取值 + 竖线 + 开关（开关型可进入行）。
     *
     * <p>左侧内容区（到竖线为止）点击进入二级界面，右侧开关只负责启用 / 关闭，
     * 两者互不干扰 —— 对应下载页的「用量阈值 / 定时清理」。
     */
    public static final int TRAILING_SWITCH_LINK = 5;

    /** 底托：中性色（不使用分类色） */
    public static final int TILE_NONE = 0;
    /** 底托：主色系（品牌色） */
    public static final int TILE_PRIMARY = 1;
    /** 底托：次色系 */
    public static final int TILE_SECONDARY = 2;
    /** 底托：辅色系 */
    public static final int TILE_TERTIARY = 3;

    private final MaterialCardView tileLeading;
    private final ImageView ivLeading;
    private final TextView tvTitle;
    private final TextView tvSupporting;
    private final TextView tvValue;
    private final ImageView ivChevron;
    private final MaterialSwitch switchTrailing;
    private final View dividerAction;

    private int tileTone = TILE_NONE;
    /** 辅助说明是否为多行（≥3 行）模式，用于切换顶部对齐 */
    private boolean multilineSupporting;
    /** 最近一次被点击的行：选择面板贴靠位置的兜底来源 */
    private static java.lang.ref.WeakReference<Md3ListItemView> lastClicked =
            new java.lang.ref.WeakReference<>(null);
    /** 最近一次按下的屏幕坐标：选择面板直接出现在手指位置附近 */
    private static float lastTouchX = Float.NaN;
    private static float lastTouchY = Float.NaN;
    private static long lastTouchAt;

    public Md3ListItemView(Context context) {
        this(context, null);
    }

    public Md3ListItemView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Md3ListItemView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(scaledHeight(R.dimen.item_height_two_line));

        LayoutInflater.from(context).inflate(R.layout.view_md3_list_item, this, true);
        tileLeading = findViewById(R.id.tile_item_leading);
        ivLeading = findViewById(R.id.iv_item_leading);
        tvTitle = findViewById(R.id.tv_item_title);
        tvSupporting = findViewById(R.id.tv_item_supporting);
        tvValue = findViewById(R.id.tv_item_value);
        ivChevron = findViewById(R.id.iv_item_chevron);
        switchTrailing = findViewById(R.id.switch_item);
        dividerAction = findViewById(R.id.divider_item_action);

        // 记录按下的屏幕坐标（不消费事件）：选择面板据此出现在手指附近
        setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.getRawX();
                lastTouchY = event.getRawY();
                lastTouchAt = android.os.SystemClock.uptimeMillis();
            }
            return false;
        });

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.Md3ListItemView);
            try {
                CharSequence title = a.getText(R.styleable.Md3ListItemView_md3Title);
                if (title != null) {
                    setTitle(title);
                }
                CharSequence supporting = a.getText(R.styleable.Md3ListItemView_md3Supporting);
                if (supporting != null) {
                    setSupporting(supporting);
                }
                CharSequence value = a.getText(R.styleable.Md3ListItemView_md3TrailingValue);
                if (value != null) {
                    setTrailingValue(value);
                }
                int tone = a.getInt(R.styleable.Md3ListItemView_md3IconTileColor, TILE_NONE);
                int icon = a.getResourceId(R.styleable.Md3ListItemView_md3LeadingIcon, 0);
                if (icon != 0) {
                    setLeadingIcon(icon, tone);
                } else {
                    tileTone = tone;
                }
                setTrailingMode(a.getInt(R.styleable.Md3ListItemView_md3TrailingMode,
                        TRAILING_NONE));
            } finally {
                a.recycle();
            }
        }
    }

    public void setTitle(CharSequence text) {
        tvTitle.setText(text);
    }

    /** 辅助说明；传 null / 空串时隐藏，并把行高回落到单行 56dp。 */
    public void setSupporting(@Nullable CharSequence text) {
        boolean has = text != null && text.length() > 0;
        tvSupporting.setText(has ? text : null);
        tvSupporting.setVisibility(has ? VISIBLE : GONE);
        setMinimumHeight(scaledHeight(
                has ? R.dimen.item_height_two_line : R.dimen.item_height_one_line));
    }

    /** 行高按「列表密度」缩放；密度改动后重建页面即生效。 */
    private int scaledHeight(int dimenRes) {
        int base = getResources().getDimensionPixelSize(dimenRes);
        return Math.round(base * AppearanceManager.densityScale(getContext()));
    }

    public void setTrailingValue(CharSequence text) {
        tvValue.setText(text);
    }

    /** 覆盖尾部取值文案的颜色（用于「未开启 / 异常」等需要语义色的取值）。 */
    public void setTrailingValueColor(ColorStateList color) {
        if (color != null) {
            tvValue.setTextColor(color);
        }
    }

    /** 设置前导图标，沿用上一次指定的底托分类色。 */
    public void setLeadingIcon(@DrawableRes int resId) {
        setLeadingIcon(resId, tileTone);
    }

    /**
     * 设置前导图标与底托分类色。
     *
     * @param tone {@link #TILE_NONE} / {@link #TILE_PRIMARY} / {@link #TILE_SECONDARY}
     *             / {@link #TILE_TERTIARY}
     */
    public void setLeadingIcon(@DrawableRes int resId, int tone) {
        tileTone = tone;
        if (resId == 0) {
            tileLeading.setVisibility(GONE);
            return;
        }
        ivLeading.setImageResource(resId);
        applyTileTone();
        tileLeading.setVisibility(VISIBLE);
    }

    /** 重新按当前偏好染色（切换「彩色图标底托」后无需重建页面即可刷新）。 */
    public void refreshLeadingTile() {
        if (tileLeading.getVisibility() == VISIBLE) {
            applyTileTone();
        }
    }

    /**
     * 按分类色给底托与图标上色。
     *
     * <p>「彩色图标底托」关闭（默认）或未指定分类色时，不画色块：
     * 图标回到 24dp 的裸线图标，与参考应用的设置列表一致。
     */
    private void applyTileTone() {
        boolean colored = AppearanceManager.iconTilesEnabled(getContext())
                && tileTone != TILE_NONE;
        if (!colored) {
            tileLeading.setCardBackgroundColor(Color.TRANSPARENT);
            ImageViewCompat.setImageTintList(ivLeading, ColorStateList.valueOf(
                    MaterialColors.getColor(tileLeading,
                            com.google.android.material.R.attr.colorOnSurfaceVariant)));
            int icon = getResources().getDimensionPixelSize(R.dimen.icon_md);
            resizeTile(icon, getResources().getDimensionPixelSize(R.dimen.spacing_lg), icon);
            return;
        }
        int backgroundAttr;
        int foregroundAttr;
        // MD3 色彩属性由 Material 库提供，须使用 material 包的 R
        switch (tileTone) {
            case TILE_PRIMARY:
                backgroundAttr = com.google.android.material.R.attr.colorPrimaryContainer;
                foregroundAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
                break;
            case TILE_SECONDARY:
                backgroundAttr = com.google.android.material.R.attr.colorSecondaryContainer;
                foregroundAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
                break;
            default:
                backgroundAttr = com.google.android.material.R.attr.colorTertiaryContainer;
                foregroundAttr = com.google.android.material.R.attr.colorOnTertiaryContainer;
                break;
        }
        tileLeading.setCardBackgroundColor(MaterialColors.getColor(tileLeading, backgroundAttr));
        ImageViewCompat.setImageTintList(ivLeading, ColorStateList.valueOf(
                MaterialColors.getColor(tileLeading, foregroundAttr)));
        resizeTile(getResources().getDimensionPixelSize(R.dimen.icon_tile_size),
                getResources().getDimensionPixelSize(R.dimen.spacing_md),
                getResources().getDimensionPixelSize(R.dimen.icon_tile_icon_size));
    }

    /** 同时调整底托尺寸、右侧间距与内部图标尺寸（裸图标与彩色底托两套几何）。 */
    private void resizeTile(int tileSize, int marginEnd, int iconSize) {
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) tileLeading.getLayoutParams();
        if (lp.width != tileSize || lp.height != tileSize || lp.getMarginEnd() != marginEnd) {
            lp.width = tileSize;
            lp.height = tileSize;
            lp.setMarginEnd(marginEnd);
            tileLeading.setLayoutParams(lp);
        }
        ViewGroup.LayoutParams iconLp = ivLeading.getLayoutParams();
        if (iconLp.width != iconSize || iconLp.height != iconSize) {
            iconLp.width = iconSize;
            iconLp.height = iconSize;
            ivLeading.setLayoutParams(iconLp);
        }
    }

    public void setTrailingMode(int mode) {
        boolean linked = mode == TRAILING_SWITCH_LINK;
        tvValue.setVisibility(
                mode == TRAILING_VALUE || mode == TRAILING_VALUE_PLAIN || linked
                        ? VISIBLE : GONE);
        ivChevron.setVisibility(
                mode == TRAILING_VALUE || mode == TRAILING_CHEVRON ? VISIBLE : GONE);
        switchTrailing.setVisibility(
                mode == TRAILING_SWITCH || linked ? VISIBLE : GONE);
        dividerAction.setVisibility(linked ? VISIBLE : GONE);
    }

    /** 切换为「取值 + 竖线 + 开关」行，返回开关供调用方绑定监听。 */
    public MaterialSwitch setTrailingSwitchLink() {
        setTrailingMode(TRAILING_SWITCH_LINK);
        return switchTrailing;
    }

    /**
     * 辅助说明允许多行完整展示（用于保存目录这类长路径）。
     *
     * @param maxLines 最大行数，超出后仍会省略以避免异常长的内容撑爆卡片
     */
    public void setSupportingMultiline(int maxLines) {
        tvSupporting.setMaxLines(Math.max(1, maxLines));
        tvSupporting.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // 三行以上的行按 MD3 规范改为「顶部对齐」：图标 / 尾部图标与首行文字对齐，
        // 而不是悬在整块内容的正中间（长路径会把行撑高，居中会让图标显得脱节）。
        boolean multi = maxLines > 2;
        if (multilineSupporting != multi) {
            multilineSupporting = multi;
            setGravity(multi ? (Gravity.TOP | Gravity.START) : Gravity.CENTER_VERTICAL);
            int v = Math.round(16f * getResources().getDisplayMetrics().density);
            setPadding(getPaddingLeft(), multi ? v : 0, getPaddingRight(), multi ? v : 0);
        }
    }

    /** 尾部取值允许多行展示（长目录、长数值组合等）。 */
    public void setTrailingValueMultiline(int maxLines) {
        tvValue.setMaxLines(Math.max(1, maxLines));
        tvValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvValue.setGravity(Gravity.END);
    }

    /** 切换为开关行并返回开关，供调用方绑定监听。 */
    public MaterialSwitch setTrailingSwitch() {
        setTrailingMode(TRAILING_SWITCH);
        return switchTrailing;
    }

    public MaterialSwitch getSwitch() {
        return switchTrailing;
    }

    /**
     * 可点击时才挂载水波纹背景。
     *
     * <p>开关行整行不可点（只有开关本身响应），避免点空白处误切换。
     */
    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        // 包一层记录「最近点击的行」：可选值行被点击后，选择面板需要贴靠到它旁边
        super.setOnClickListener(l == null ? null : v -> {
            lastClicked = new java.lang.ref.WeakReference<>(this);
            l.onClick(v);
        });
        setClickable(l != null);
        setFocusable(l != null);
        if (l != null && getBackground() == null) {
            TypedValue value = new TypedValue();
            if (getContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, value, true)) {
                setBackgroundResource(value.resourceId);
            }
        }
    }

    /** 最近一次被点击的行；没有记录时返回 null。 */
    @Nullable
    static View lastClickedRow() {
        return lastClicked.get();
    }

    /**
     * 最近一次按下的屏幕坐标（x, y）。
     *
     * <p>超过 1.5 秒视为过期 —— 避免用户很久之后再从别处触发选择面板时，
     * 面板还飞到上一次点击的位置。
     */
    @Nullable
    static float[] lastTouchPoint() {
        if (Float.isNaN(lastTouchX)
                || android.os.SystemClock.uptimeMillis() - lastTouchAt > 1500L) {
            return null;
        }
        return new float[]{lastTouchX, lastTouchY};
    }

    /** 置灰时把子控件一并禁用（ViewGroup 不会自动下发）。 */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        tileLeading.setEnabled(enabled);
        ivLeading.setEnabled(enabled);
        tvTitle.setEnabled(enabled);
        tvSupporting.setEnabled(enabled);
        tvValue.setEnabled(enabled);
        ivChevron.setEnabled(enabled);
        switchTrailing.setEnabled(enabled);
    }
}
