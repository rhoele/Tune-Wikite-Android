package com.auramusic.wiki.classic;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.slider.Slider;

/**
 * 设置二级页：外观。
 *
 * <p>页面分为三块：
 * <ul>
 *   <li><b>实时预览</b>：主色 / 次色 / 辅色 / 容器四张色卡 + 一个示例卡片，
 *       全部直接引用当前主题令牌，切换调色板后与全局同步变化；</li>
 *   <li><b>外观基础</b>：主题模式用三列分段卡片（直角描边，选中变圆角矩形），
 *       调色板用带色点的单选弹窗，显示大小用滑杆（0.85 – 1.30，步长 0.05）；</li>
 *   <li><b>交互与动效</b>：动效强度、振动、底栏样式与玻璃材质、图标底托开关。</li>
 * </ul>
 *
 * <p>改动即时生效：主题模式交给 AppCompatDelegate 重建活动栈；调色板与字号写入偏好后
 * 本页 recreate，其余页面在 onResume 依外观修订号同步重建；动效 / 触感 / 底栏 / 底托
 * 在绘制时即时读取，无需重建。
 */
public class AppearanceSettingsActivity extends BaseActivity {

    private static final float[] DENSITY_SCALES = {
            AppearanceManager.DENSITY_COMPACT,
            AppearanceManager.DENSITY_STANDARD,
            AppearanceManager.DENSITY_RELAXED,
    };

    private static final float[] MOTION_SCALES = {
            AppearanceManager.MOTION_OFF,
            AppearanceManager.MOTION_STANDARD,
            AppearanceManager.MOTION_EMPHASIZED,
    };

    private static final int[] HAPTIC_LEVELS = {
            AppearanceManager.HAPTIC_LIGHT,
            AppearanceManager.HAPTIC_STANDARD,
            AppearanceManager.HAPTIC_STRONG,
    };

    private static final String[] NAV_STYLES = {
            AppearanceManager.NAV_FLOATING,
            AppearanceManager.NAV_ATTACHED,
    };

    private static final int[] NAV_GLASS_STYLES = {
            AppearanceManager.GLASS_NONE,
            AppearanceManager.GLASS_ACRYLIC,
            AppearanceManager.GLASS_LIQUID,
    };

    /** 显示大小滑杆的步长，与布局中的 android:stepSize 保持一致 */
    private static final float FONT_STEP = 0.05f;

    /** 主题模式分组的外缘圆角（dp），与「分组独立卡片」一致 */
    private static final float SEGMENT_OUTER_RADIUS_DP = 24f;
    /** 相邻两列之间的内缘圆角（dp），接近直角 */
    private static final float SEGMENT_INNER_RADIUS_DP = 8f;
    /** 主题模式圆角施加 / 撤销的时长 */
    private static final long SEGMENT_SHAPE_MS = 220L;

    /** 重建页面时保留滚动位置的状态键 */
    private static final String STATE_SCROLL_Y = "appearance_scroll_y";

    /** 主题模式的顺序：与 {@link #THEME_BUTTON_IDS} 一一对应 */
    private static final int[] THEME_MODES = {
            AppearanceManager.THEME_SYSTEM,
            AppearanceManager.THEME_LIGHT,
            AppearanceManager.THEME_DARK,
    };

    private static final int[] THEME_BUTTON_IDS = {
            R.id.btn_theme_system,
            R.id.btn_theme_light,
            R.id.btn_theme_dark,
    };

    private static final int[] THEME_ICON_IDS = {
            R.id.iv_theme_system,
            R.id.iv_theme_light,
            R.id.iv_theme_dark,
    };

    private static final int[] THEME_LABEL_IDS = {
            R.id.tv_theme_system,
            R.id.tv_theme_light,
            R.id.tv_theme_dark,
    };

    /** 仅「改完不重建页面」的项目需要持有行引用以便就地刷新取值 */
    private View rowMotion;
    private View rowHapticLevel;
    private View rowNavStyle;
    private View rowNavGlass;
    private MaterialSwitch switchHaptics;
    private MaterialSwitch switchBlur;
    private NestedScrollView scrollView;
    private ChipGroup variantGroup;
    /** 主题模式的三列分段容器 */
    private LinearLayout themeSegments;
    /** 主题模式三列当前的圆角（dp，TL/TR/BR/BL），用于施加 / 撤销动效 */
    private final float[][] segmentCorners = new float[THEME_BUTTON_IDS.length][4];
    private final ValueAnimator[] segmentAnimators = new ValueAnimator[THEME_BUTTON_IDS.length];
    private Slider sliderFont;
    private TextView tvFontValue;
    private TextView tvFontPreview;
    private TextView tvPreviewPalette;
    private TextView tvPaletteCurrent;
    private TextView tvPaletteHint;
    private TextView tvBlurDesc;

    /** 回填 / 构建行期间抑制监听回调，避免把初始化当成用户操作 */
    private boolean binding;

    /**
     * 待补显示的「已应用」提示。
     *
     * <p>部分外观项改完会立即重建页面（{@link #reloadSelf()}），挂在旧视图上的 Snackbar
     * 会随窗口一起销毁；把文案暂存到静态字段，由新页面的 onCreate 补一次。
     */
    private static CharSequence pendingAppliedMessage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_appearance);
        bindToolbar(R.string.appearance_title);

        CharSequence pending = pendingAppliedMessage;
        pendingAppliedMessage = null;
        if (pending != null) {
            getWindow().getDecorView().post(() -> Feedback.show(this, pending));
        }

        tvPaletteHint = findViewById(R.id.tv_palette_hint);
        tvBlurDesc = findViewById(R.id.tv_blur_desc);
        tvFontValue = findViewById(R.id.tv_font_value);
        tvFontPreview = findViewById(R.id.tv_font_preview);
        tvPreviewPalette = findViewById(R.id.tv_preview_palette);
        tvPaletteCurrent = findViewById(R.id.tv_palette_current);
        scrollView = findViewById(R.id.appearance_scroll);
        sliderFont = findViewById(R.id.slider_font);
        themeSegments = findViewById(R.id.theme_segments);

        updatePaletteHint();
        tvPreviewPalette.setText(paletteValue().value());
        tvPaletteCurrent.setText(paletteValue().value());

        binding = true;
        buildRows();
        bindThemeCards();
        // 主题模式 / 调色板 / 显示大小 / 设置项样式属于同一个分组：
        // 必须在行构建完成后再统一决定「一张整卡」还是「四张独立卡片」
        applyAppearanceGroupStyle();
        renderThemeCards();
        bindFontSlider();
        renderPaletteStrip();
        bindVariantChips();
        binding = false;

        updateExperienceEnabledState();
        restoreScroll(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置 / 其他页面返回，或明暗未变导致未重建时，保证选中态与偏好一致
        if (!binding) {
            renderThemeCards();
            syncVariantChips();
        }
    }

    // ------------------------------------------------------------------
    // 预览：主题模式单选卡 / 显示大小滑杆
    // ------------------------------------------------------------------

    /**
     * 主题模式：左中右三列分段选择。
     *
     * <p>三列都是可点击的卡片：未选中是「直角 + 描边」，选中列补间到圆角矩形并填充
     * primaryContainer。颜色与形状在这里集中同步，避免状态色与形状各管一半。
     */
    private void bindThemeCards() {
        if (themeSegments == null) {
            return;
        }
        for (int i = 0; i < THEME_BUTTON_IDS.length; i++) {
            final int index = i;
            View card = themeSegments.findViewById(THEME_BUTTON_IDS[i]);
            if (card != null) {
                card.setOnClickListener(v -> pickTheme(index));
            }
            segmentCorners[i] = baseCorners(i);
        }
        // 初始形态：分组圆角 + 当前选中列落成统一大圆角（不播动画）
        applyThemeSelection(indexOfTheme(AppearanceManager.themeMode(this)), false);
    }

    /** 切换主题：先播完圆角动效，再让 AppCompat 重建页面。 */
    private void pickTheme(int index) {
        int mode = THEME_MODES[index];
        if (mode == AppearanceManager.themeMode(this)) {
            return;
        }
        HapticsManager.toggle(themeSegments);
        applyThemeSelection(index, true);
        // 主题切换会重建活动栈；延后一点，让「直角 → 圆角」的施加动效可见
        themeSegments.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            AppearanceManager.setThemeMode(this, mode);
            toastApplied();
        }, SEGMENT_SHAPE_MS + 40L);
    }

    /** 同步三列的选中态：填充色 / 描边 / 图标文字着色 + 圆角。 */
    private void applyThemeSelection(int index, boolean animated) {
        View anchor = themeSegments != null ? themeSegments : getWindow().getDecorView();
        int filled = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorPrimaryContainer);
        int filledContent = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnPrimaryContainer);
        int idleFill = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorSurfaceContainerLow);
        int idleOutline = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOutline);
        int activeOutline = MaterialColors.getColor(anchor,
                androidx.appcompat.R.attr.colorPrimary);
        int idleContent = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (int i = 0; i < THEME_BUTTON_IDS.length; i++) {
            MaterialCardView card = findViewById(THEME_BUTTON_IDS[i]);
            if (card == null) {
                continue;
            }
            boolean checked = i == index;
            // 未选中也保留控件自身的容器色，不能与分组卡背景混成一片
            card.setCardBackgroundColor(checked ? filled : idleFill);
            card.setStrokeColor(ColorStateList.valueOf(checked ? activeOutline : idleOutline));
            int content = checked ? filledContent : idleContent;
            TextView label = findViewById(THEME_LABEL_IDS[i]);
            ImageView icon = findViewById(THEME_ICON_IDS[i]);
            if (label != null) {
                label.setTextColor(content);
            }
            if (icon != null) {
                icon.setImageTintList(ColorStateList.valueOf(content));
            }
            animateSegmentCorners(i, checked, animated);
        }
    }

    /**
     * 未选中时的分组形态：最左 / 最右列的外缘大圆角，内侧与相邻列接近直角。
     *
     * @return 四角半径（dp），顺序为左上、右上、右下、左下
     */
    private static float[] baseCorners(int index) {
        boolean first = index == 0;
        boolean last = index == THEME_BUTTON_IDS.length - 1;
        float left = first ? SEGMENT_OUTER_RADIUS_DP : SEGMENT_INNER_RADIUS_DP;
        float right = last ? SEGMENT_OUTER_RADIUS_DP : SEGMENT_INNER_RADIUS_DP;
        return new float[]{left, right, right, left};
    }

    /** 选中列：四角统一为外缘圆角，间隔圆角与边缘圆角一起补间。 */
    private static float[] selectedCorners() {
        return new float[]{SEGMENT_OUTER_RADIUS_DP, SEGMENT_OUTER_RADIUS_DP,
                SEGMENT_OUTER_RADIUS_DP, SEGMENT_OUTER_RADIUS_DP};
    }

    /** 主题模式对应的列索引；找不到时回落到「跟随系统」。 */
    private static int indexOfTheme(int mode) {
        for (int i = 0; i < THEME_MODES.length; i++) {
            if (THEME_MODES[i] == mode) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 主题模式分段卡片的形状动效。
     *
     * <p>未选中时沿用「分组独立卡片」的四角几何；选中列的四个角一起补间到外缘圆角，
     * 因此间隔侧的圆角过渡与边缘侧完全同步（撤销时同样整体退回分组形态）。
     *
     * @param animated 为 false 时直接落到目标形状（初始化 / 回填）
     */
    private void animateSegmentCorners(int index, boolean selected, boolean animated) {
        MaterialCardView card = findViewById(THEME_BUTTON_IDS[index]);
        if (card == null) {
            return;
        }
        final float[] target = selected ? selectedCorners() : baseCorners(index);
        final float[] from = segmentCorners[index].clone();
        if (segmentAnimators[index] != null) {
            segmentAnimators[index].cancel();
            segmentAnimators[index] = null;
        }
        boolean same = true;
        for (int c = 0; c < 4; c++) {
            if (Math.abs(from[c] - target[c]) > 0.01f) {
                same = false;
                break;
            }
        }
        if (!animated || same || !MotionManager.animationsEnabled(this)) {
            System.arraycopy(target, 0, segmentCorners[index], 0, 4);
            applySegmentCorners(card, target);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SEGMENT_SHAPE_MS);
        animator.setInterpolator(MotionManager.standard(this));
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float[] current = segmentCorners[index];
            for (int c = 0; c < 4; c++) {
                current[c] = from[c] + (target[c] - from[c]) * t;
            }
            applySegmentCorners(card, current);
        });
        segmentAnimators[index] = animator;
        animator.start();
    }

    /** 把四角半径（dp，TL/TR/BR/BL）应用到分段卡片。 */
    private void applySegmentCorners(MaterialCardView card, float[] radiusDp) {
        float density = getResources().getDisplayMetrics().density;
        card.setShapeAppearanceModel(new ShapeAppearanceModel.Builder()
                .setTopLeftCornerSize(radiusDp[0] * density)
                .setTopRightCornerSize(radiusDp[1] * density)
                .setBottomRightCornerSize(radiusDp[2] * density)
                .setBottomLeftCornerSize(radiusDp[3] * density)
                .build());
    }

    private void renderThemeCards() {
        if (themeSegments == null) {
            return;
        }
        applyThemeSelection(indexOfTheme(AppearanceManager.themeMode(this)), false);
    }

    /**
     * 「外观基础」是<b>一个整体分组</b>：主题模式 / 调色板 / 显示大小 / 设置项样式
     * 必须在同一时刻切换形态，避免只有主题模式跟着变。
     *
     * <ul>
     *   <li><b>整卡分组</b>：外层卡可见（Highest + 24dp 圆角 + 20dp 内边距），
     *       四个分区卡全部透明、零圆角、零间距，视觉上合并成一张卡；</li>
     *   <li><b>独立卡片</b>：外层卡让位（透明 + 直角），四个分区各自成为
     *       一张 24dp 圆角卡片，卡片间距 {@code list_item_spacing}。</li>
     * </ul>
     */
    private void applyAppearanceGroupStyle() {
        MaterialCardView group = findViewById(R.id.card_appearance_group);
        LinearLayout content = findViewById(R.id.appearance_group_content);
        if (group == null || content == null) {
            return;
        }
        boolean separate = AppearanceManager.rowsAsCards(this);
        int inset = getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        float cardRadius = getResources().getDimension(R.dimen.shape_card);
        // 与「交互与动效」一致：独立卡片组内卡间距 4dp，首/末卡的大圆角由 applyGroupedCorners 处理
        int gap = dp(4);

        group.setCardBackgroundColor(separate ? Color.TRANSPARENT
                : MaterialColors.getColor(group,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
        group.setStrokeWidth(0);
        group.setRadius(separate ? 0f : cardRadius);
        content.setPadding(separate ? 0 : inset, separate ? 0 : dp(8),
                separate ? 0 : inset, separate ? 0 : dp(12));

        // 四个分区：整卡分组时全部并入外层卡；独立卡片时各自成卡
        int[] sectionCards = {
                R.id.card_theme_section, R.id.card_palette,
                R.id.card_display_size, R.id.card_appearance_row_style,
        };
        int[] sectionContents = {
                R.id.theme_section_content, R.id.palette_content,
                R.id.display_size_content, R.id.list_appearance_row_style_host,
        };
        for (int i = 0; i < sectionCards.length; i++) {
            MaterialCardView card = findViewById(sectionCards[i]);
            View inner = findViewById(sectionContents[i]);
            if (card == null || inner == null) {
                continue;
            }
            ViewGroup.LayoutParams raw = card.getLayoutParams();
            if (raw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                lp.topMargin = 0;
                lp.bottomMargin = separate && i != sectionCards.length - 1 ? gap : 0;
                card.setLayoutParams(lp);
            }
            card.setCardElevation(0f);
            if (separate) {
                card.setCardBackgroundColor(MaterialColors.getColor(card,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh));
                card.setStrokeWidth(0);
                // 初始 8dp，首卡的 24dp 上两角 / 末卡的 24dp 下两角在循环后统一收边
                card.setRadius(getResources().getDimension(R.dimen.shape_sm));
                // 独立卡片的上下留白收紧，贴近「交互与动效」行卡的高度节奏
                int sectionPadV = i == 3 ? dp(4) : dp(12);
                inner.setPadding(inset, sectionPadV, inset, sectionPadV);
            } else {
                card.setCardBackgroundColor(Color.TRANSPARENT);
                card.setStrokeWidth(0);
                card.setRadius(0f);
                // 外层卡已经提供左右 20dp；分区之间用上间距区隔，最后一块不需要
                inner.setPadding(0, i == 0 ? dp(8) : dp(16), 0, 0);
            }
        }

        if (separate) {
            // 首卡上两角 24dp / 下两角 8dp，中间卡四角 8dp，末卡上两角 8dp / 下两角 24dp
            SettingsRows.applyGroupedCorners(content);
        }
    }

    private void bindFontSlider() {
        float scale = snapToStep(AppearanceManager.fontScale(this));
        sliderFont.setValue(scale);
        renderFontValue(scale);
        sliderFont.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                renderFontValue(value);
            }
        });
        sliderFont.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                // 统一吸附到 5% 档位，避免出现 93%、108% 这类非整档数值
                float value = snapToStep(slider.getValue());
                slider.setValue(value);
                if (Math.abs(value - AppearanceManager.fontScale(
                        AppearanceSettingsActivity.this)) < 0.001f) {
                    return;
                }
                AppearanceManager.setFontScale(AppearanceSettingsActivity.this, value);
                toastApplied();
                reloadSelf();
            }
        });
    }

    /** 拖动过程中只更新文案与示例字号，松手才落盘并重建。 */
    private void renderFontValue(float scale) {
        float snapped = snapToStep(scale);
        int percent = Math.round(snapped * 100f);
        tvFontValue.setText(getString(R.string.appearance_display_size_value,
                getString(AppearanceManager.fontLabelRes(snapped)), percent));
        // BodySmall 基准 12sp，按滑杆比例实时缩放示例文字
        tvFontPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * snapped);
    }

    private static float snapToStep(float scale) {
        float snapped = Math.round(scale / FONT_STEP) * FONT_STEP;
        return Math.max(AppearanceManager.FONT_SMALL,
                Math.min(AppearanceManager.FONT_XLARGE, snapped));
    }

    // ------------------------------------------------------------------
    // 行构建
    // ------------------------------------------------------------------

    private void buildRows() {
        LinearLayout experience = findViewById(R.id.list_experience);
        boolean asCards = AppearanceManager.rowsAsCards(this);

        rowMotion = SettingsRows.addOptionRow(getLayoutInflater(), experience,
                R.string.appearance_motion, R.string.appearance_option_motion_desc,
                motionValue(), this::pickMotion,
                R.drawable.ic_motion, Md3ListItemView.TILE_PRIMARY, asCards);
        switchHaptics = SettingsRows.addSwitchRow(getLayoutInflater(), experience,
                R.string.appearance_haptics, R.string.appearance_option_haptics_desc,
                AppearanceManager.hapticsEnabled(this), this::onHapticsToggled,
                R.drawable.ic_vibration, Md3ListItemView.TILE_SECONDARY, asCards);
        rowHapticLevel = SettingsRows.addOptionRow(getLayoutInflater(), experience,
                R.string.appearance_haptic_level, R.string.appearance_option_haptic_level_desc,
                hapticValue(), this::pickHapticLevel,
                R.drawable.ic_tune, Md3ListItemView.TILE_TERTIARY, asCards);
        rowNavStyle = SettingsRows.addOptionRow(getLayoutInflater(), experience,
                R.string.appearance_nav_style, R.string.appearance_option_nav_desc,
                navValue(), this::pickNavStyle,
                R.drawable.ic_nav_style, Md3ListItemView.TILE_PRIMARY, asCards);
        // 底栏材质：关闭 / 亚克力 / 液态玻璃。开启后底栏与页面选择器共用同一块玻璃层，
        // 页面滚动时模糊实时跟随（见 MainActivity#applyNavStyle 与 BackdropBlurView）。
        rowNavGlass = SettingsRows.addOptionRow(getLayoutInflater(), experience,
                R.string.appearance_nav_glass, R.string.appearance_option_nav_glass_desc,
                navGlassValue(), this::pickNavGlass,
                R.drawable.ic_glass, Md3ListItemView.TILE_SECONDARY, asCards);
        SettingsRows.addOptionRow(getLayoutInflater(), experience,
                R.string.appearance_density, R.string.appearance_density_desc,
                densityValue(), this::pickDensity,
                R.drawable.ic_density, Md3ListItemView.TILE_SECONDARY, asCards);
        SettingsRows.addSwitchRow(getLayoutInflater(), experience,
                R.string.appearance_icon_tiles, R.string.appearance_icon_tiles_desc,
                AppearanceManager.iconTilesEnabled(this), this::onIconTilesToggled,
                R.drawable.ic_palette, Md3ListItemView.TILE_PRIMARY, asCards);
        // 设置项样式属于「外观基础」整体分组，由 applyAppearanceGroupStyle 统一决定形态
        LinearLayout base = findViewById(R.id.list_appearance_base);
        SettingsRows.addOptionRow(getLayoutInflater(), base,
                R.string.appearance_row_cards, R.string.appearance_row_cards_desc,
                () -> getString(AppearanceManager.rowsAsCards(this)
                        ? R.string.appearance_row_cards_separate
                        : R.string.appearance_row_cards_grouped),
                this::pickRowStyle,
                R.drawable.ic_text_fields, Md3ListItemView.TILE_TERTIARY, false);
        // 模糊的说明单独用 tv_blur_desc 承载，因此必须放在卡片最后一行，
        // 否则注释会与所属的开关行被其他行隔开。
        switchBlur = SettingsRows.addSwitchRow(getLayoutInflater(), experience,
                R.string.appearance_blur, 0,
                AppearanceManager.blurEnabled(this), this::onBlurToggled,
                R.drawable.ic_contrast, Md3ListItemView.TILE_TERTIARY, asCards);

        applyRowHostStyle(experience, asCards);
        if (asCards) {
            SettingsRows.applyGroupedCorners(experience);
        }
    }

    /**
     * 独立卡片模式下把分组卡外壳变透明并去掉内边距，
     * 让每一行自己的圆角卡片直接落在页面底色上（参考应用的设置页形态）。
     */
    private void applyRowHostStyle(LinearLayout list, boolean asCards) {
        if (list == null || !(list.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup inner = (ViewGroup) list.getParent();
        int inset = getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        boolean experience = list.getId() == R.id.list_experience;
        if (asCards) {
            inner.setPadding(0, 0, 0, 0);
            if (inner.getParent() instanceof MaterialCardView) {
                MaterialCardView card = (MaterialCardView) inner.getParent();
                card.setCardBackgroundColor(Color.TRANSPARENT);
                card.setStrokeWidth(0);
                // 外层卡若保留 24dp 圆角会裁掉首/末行卡的角，导致上下圆角看起来不一致
                card.setRadius(0f);
            }
            if (experience && tvBlurDesc != null) {
                tvBlurDesc.setPadding(inset, dp(8), inset, dp(8));
            }
        } else {
            inner.setPadding(inset, dp(8), inset, dp(12));
            if (inner.getParent() instanceof MaterialCardView) {
                MaterialCardView card = (MaterialCardView) inner.getParent();
                card.setCardBackgroundColor(MaterialColors.getColor(card,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
                card.setStrokeWidth(0);
                card.setRadius(getResources().getDimension(R.dimen.shape_card));
            }
            if (experience && tvBlurDesc != null) {
                tvBlurDesc.setPadding(0, dp(8), 0, 0);
            }
        }
    }

    private void pickRowStyle() {
        String[] items = {
                getString(R.string.appearance_row_cards_grouped),
                getString(R.string.appearance_row_cards_separate),
        };
        int checked = AppearanceManager.rowsAsCards(this) ? 1 : 0;
        SettingsRows.showSingleChoice(this, R.string.appearance_row_cards, items, checked, which -> {
            AppearanceManager.setRowsAsCards(this, which == 1);
            toastApplied();
            reloadSelf();
        });
    }

    /** 调色板下方的说明：区分「动态可用性」与「动态 + 非标准方案」两种情况。 */
    private void updatePaletteHint() {
        if (!AppearanceManager.dynamicColorSupported()) {
            tvPaletteHint.setText(R.string.appearance_dynamic_unavailable);
            return;
        }
        boolean dynamic = AppearanceManager.PALETTE_DYNAMIC.equals(
                AppearanceManager.palette(this));
        boolean nonTonal = !AppearanceManager.VARIANT_TONAL.equals(
                AppearanceManager.variant(this));
        tvPaletteHint.setText(dynamic && nonTonal
                ? R.string.appearance_dynamic_variant_note
                : R.string.appearance_palette_dynamic_desc);
    }

    private SettingsRows.ValueProvider paletteValue() {
        return () -> getString(AppearanceManager.paletteLabelRes(
                AppearanceManager.paletteKey(AppearanceManager.palette(this),
                        AppearanceManager.variant(this))));
    }

    private SettingsRows.ValueProvider densityValue() {
        return () -> getString(
                AppearanceManager.densityLabelRes(AppearanceManager.densityScale(this)));
    }

    private SettingsRows.ValueProvider motionValue() {
        return () -> getString(AppearanceManager.motionLabelRes(AppearanceManager.motionScale(this)));
    }

    private SettingsRows.ValueProvider hapticValue() {
        return () -> getString(
                AppearanceManager.hapticLevelLabelRes(AppearanceManager.hapticLevel(this)));
    }

    private SettingsRows.ValueProvider navValue() {
        return () -> getString(AppearanceManager.navStyleLabelRes(AppearanceManager.navStyle(this)));
    }

    private SettingsRows.ValueProvider navGlassValue() {
        return () -> getString(
                AppearanceManager.navGlassLabelRes(AppearanceManager.navGlassStyle(this)));
    }

    // ------------------------------------------------------------------
    // 调色板：圆形色点横向条
    // ------------------------------------------------------------------

    /**
     * 渲染一整条圆形色点：动态取色在最前（三色渐变圆），其后是 9 套预设。
     * 选中项加粗描边并叠加勾选，点击即刻应用（页面重建）。
     */
    private void renderPaletteStrip() {
        LinearLayout strip = findViewById(R.id.palette_strip);
        if (strip == null) {
            return;
        }
        strip.removeAllViews();
        final String current = AppearanceManager.palette(this);
        final String currentSeed = AppearanceManager.paletteSeed(this);
        final String currentVariant = AppearanceManager.variant(this);
        int cellSize = dp(56);
        int circleSize = dp(40);

        for (final String seed : paletteKeys()) {
            final boolean dynamic = AppearanceManager.PALETTE_DYNAMIC.equals(seed);
            // 动态取色只有一个圆点；预设按「种子」比选中，圆点颜色跟随当前方案
            boolean selected = dynamic ? AppearanceManager.PALETTE_DYNAMIC.equals(current)
                    : seed.equals(currentSeed);
            final String swatchKey = dynamic
                    ? AppearanceManager.PALETTE_DYNAMIC
                    : AppearanceManager.paletteKey(seed, currentVariant);

            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams cellLp =
                    new LinearLayout.LayoutParams(cellSize, cellSize);
            cellLp.setMarginEnd(dp(4));
            cell.setLayoutParams(cellLp);
            cell.setContentDescription(getString(AppearanceManager.paletteLabelRes(swatchKey)));
            TypedValue ripple = new TypedValue();
            if (getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                cell.setBackgroundResource(ripple.resourceId);
            }

            View circle = new View(this);
            circle.setLayoutParams(new FrameLayout.LayoutParams(
                    circleSize, circleSize, Gravity.CENTER));
            circle.setBackground(swatchDrawable(swatchKey, selected));
            circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(circle);

            if (selected) {
                ImageView check = new ImageView(this);
                check.setLayoutParams(new FrameLayout.LayoutParams(
                        dp(18), dp(18), Gravity.CENTER));
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(
                        this, AppearanceManager.paletteOnPrimaryColorRes(swatchKey))));
                check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cell.addView(check);
            }

            cell.setOnClickListener(v -> {
                HapticsManager.tap(v);
                applyPalette(swatchKey);
            });
            strip.addView(cell);
        }
    }

    // ------------------------------------------------------------------
    // 色彩方案变体（标准 / 鲜明 / 表现 / 中性）
    // ------------------------------------------------------------------

    private void bindVariantChips() {
        variantGroup = findViewById(R.id.variant_group);
        if (variantGroup == null) {
            return;
        }
        variantGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (binding || checkedIds.isEmpty()) {
                return;
            }
            String variant = variantOfChip(checkedIds.get(0));
            if (variant == null || variant.equals(AppearanceManager.variant(this))) {
                // 回填时 setChecked 也会走到这里，先挡掉同值调用，避免无谓重建
                return;
            }
            boolean dynamic = AppearanceManager.PALETTE_DYNAMIC.equals(
                    AppearanceManager.palette(this));
            if (dynamic && AppearanceManager.VARIANT_TONAL.equals(variant)) {
                // 动态取色本身就是标准方案，保留选择但不需要换主题
                AppearanceManager.setVariant(this, variant);
                reloadSelf();
                return;
            }
            if (dynamic) {
                // 系统动态取色只提供一套令牌；要使用其他方案，就取当前壁纸主色的
                // 最近种子色，再套用用户选中的方案。
                int seedColor = MaterialColors.getColor(getWindow().getDecorView(),
                        androidx.appcompat.R.attr.colorPrimary);
                String seed = nearestSeedKey(seedColor);
                AppearanceManager.setVariant(this, variant);
                AppearanceManager.setPalette(this, seed);
                showApplied(getString(R.string.appearance_variant_from_wallpaper,
                        getString(AppearanceManager.paletteLabelRes(
                                AppearanceManager.paletteKey(seed, variant)))), true);
                reloadSelf();
                return;
            }
            AppearanceManager.setVariant(this, variant);
            toastApplied();
            reloadSelf();
        });
        syncVariantChips();
    }

    private void syncVariantChips() {
        String variant = AppearanceManager.variant(this);
        int checkedId = R.id.chip_variant_tonal;
        if (AppearanceManager.VARIANT_VIBRANT.equals(variant)) {
            checkedId = R.id.chip_variant_vibrant;
        } else if (AppearanceManager.VARIANT_EXPRESSIVE.equals(variant)) {
            checkedId = R.id.chip_variant_expressive;
        } else if (AppearanceManager.VARIANT_NEUTRAL.equals(variant)) {
            checkedId = R.id.chip_variant_neutral;
        }
        Chip chip = variantGroup.findViewById(checkedId);
        if (chip != null) {
            chip.setChecked(true);
        }

    }

    /** 在 9 个预设种子里找与目标色色相最接近的一个。 */
    private String nearestSeedKey(int targetColor) {
        float[] targetHsv = new float[3];
        Color.colorToHSV(targetColor, targetHsv);
        String best = AppearanceManager.PALETTE_RED;
        float bestDistance = Float.MAX_VALUE;
        for (String key : AppearanceManager.PRESET_PALETTES) {
            float[] hsv = new float[3];
            Color.colorToHSV(ContextCompat.getColor(this,
                    AppearanceManager.palettePrimaryColorRes(key)), hsv);
            float diff = Math.abs(hsv[0] - targetHsv[0]);
            float distance = Math.min(diff, 360f - diff);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = key;
            }
        }
        return best;
    }

    private static String variantOfChip(int checkedId) {
        if (checkedId == R.id.chip_variant_vibrant) {
            return AppearanceManager.VARIANT_VIBRANT;
        }
        if (checkedId == R.id.chip_variant_expressive) {
            return AppearanceManager.VARIANT_EXPRESSIVE;
        }
        if (checkedId == R.id.chip_variant_neutral) {
            return AppearanceManager.VARIANT_NEUTRAL;
        }
        return AppearanceManager.VARIANT_TONAL;
    }

    /**
     * 调色板色点：预设色用主色实心圆，动态取色用主/次/辅三色渐变圆；
     * 未选中用 1dp outlineVariant 描边，选中用 3dp onSurface 描边（参考应用的选中环）。
     */
    private Drawable swatchDrawable(String key, boolean selected) {
        View root = getWindow().getDecorView();
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        if (AppearanceManager.PALETTE_DYNAMIC.equals(key)) {
            drawable.setOrientation(GradientDrawable.Orientation.TL_BR);
            drawable.setColors(new int[]{
                    MaterialColors.getColor(root,
                            androidx.appcompat.R.attr.colorPrimary),
                    MaterialColors.getColor(root,
                            com.google.android.material.R.attr.colorSecondary),
                    MaterialColors.getColor(root,
                            com.google.android.material.R.attr.colorTertiary),
            });
        } else {
            drawable.setColor(ContextCompat.getColor(this,
                    AppearanceManager.palettePrimaryColorRes(key)));
        }
        drawable.setStroke(selected ? dp(3) : dp(1),
                MaterialColors.getColor(root, selected
                        ? com.google.android.material.R.attr.colorOnSurface
                        : com.google.android.material.R.attr.colorOutlineVariant));
        return drawable;
    }

    private void applyPalette(String key) {
        String currentKey = AppearanceManager.paletteKey(
                AppearanceManager.palette(this), AppearanceManager.variant(this));
        if (key.equals(currentKey) || key.equals(AppearanceManager.palette(this))) {
            return;
        }
        if (AppearanceManager.PALETTE_DYNAMIC.equals(key)
                && !AppearanceManager.dynamicColorSupported()) {
            // 动态取色不可用时说明原因；这条比「已应用」更需要用户看到
            AppearanceManager.setPalette(this, key);
            showApplied(getString(R.string.appearance_dynamic_unavailable), true);
            reloadSelf();
            return;
        }
        AppearanceManager.setPalette(this, key);
        toastApplied();
        reloadSelf();
    }

    // ------------------------------------------------------------------
    // 其余可选值项的原生单选子菜单
    // ------------------------------------------------------------------

    private void pickDensity() {
        String[] items = {
                getString(R.string.appearance_density_compact),
                getString(R.string.appearance_density_standard),
                getString(R.string.appearance_density_relaxed),
        };
        int checked = indexOfFloat(DENSITY_SCALES, AppearanceManager.densityScale(this));
        SettingsRows.showSingleChoice(this, R.string.appearance_density, items, checked, which -> {
            AppearanceManager.setDensityScale(this, DENSITY_SCALES[which]);
            toastApplied();
            // 行高在构造时读取密度，需要重建本页才能看到效果
            reloadSelf();
        });
    }

    private void pickMotion() {
        String[] items = {
                getString(R.string.appearance_motion_off),
                getString(R.string.appearance_motion_standard),
                getString(R.string.appearance_motion_emphasized),
        };
        int checked = indexOfFloat(MOTION_SCALES, AppearanceManager.motionScale(this));
        SettingsRows.showSingleChoice(this, R.string.appearance_motion, items, checked, which -> {
            AppearanceManager.setMotionScale(this, MOTION_SCALES[which]);
            SettingsRows.refreshValue(rowMotion, motionValue());
            toastApplied();
        });
    }

    private void pickHapticLevel() {
        String[] items = {
                getString(R.string.appearance_haptic_light),
                getString(R.string.appearance_haptic_standard),
                getString(R.string.appearance_haptic_strong),
        };
        int checked = indexOfInt(HAPTIC_LEVELS, AppearanceManager.hapticLevel(this));
        SettingsRows.showSingleChoice(this, R.string.appearance_haptic_level, items, checked, which -> {
            AppearanceManager.setHapticLevel(this, HAPTIC_LEVELS[which]);
            SettingsRows.refreshValue(rowHapticLevel, hapticValue());
            // 立即用新强度给一次触感，便于用户当场比较
            HapticsManager.toggle(rowHapticLevel);
            toastApplied();
        });
    }

    private void pickNavStyle() {
        String[] items = {
                getString(R.string.appearance_nav_floating),
                getString(R.string.appearance_nav_attached),
        };
        int checked = indexOfString(NAV_STYLES, AppearanceManager.navStyle(this));
        SettingsRows.showSingleChoice(this, R.string.appearance_nav_style, items, checked, which -> {
            AppearanceManager.setNavStyle(this, NAV_STYLES[which]);
            SettingsRows.refreshValue(rowNavStyle, navValue());
            toastApplied();
        });
    }

    /** 底栏（及页面选择器）的背景材质：关闭即用不透明表面色，不需要任何机型能力 */
    private void pickNavGlass() {
        String[] items = {
                getString(R.string.appearance_nav_glass_none),
                getString(R.string.appearance_nav_glass_acrylic),
                getString(R.string.appearance_nav_glass_liquid),
        };
        int checked = indexOfInt(NAV_GLASS_STYLES, AppearanceManager.navGlassStyle(this));
        SettingsRows.showSingleChoice(this, R.string.appearance_nav_glass, items, checked, which -> {
            AppearanceManager.setNavGlassStyle(this, NAV_GLASS_STYLES[which]);
            SettingsRows.refreshValue(rowNavGlass, navGlassValue());
            toastApplied();
        });
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    private void onHapticsToggled(CompoundButton buttonView, boolean isChecked) {
        if (binding) {
            return;
        }
        HapticsManager.toggle(buttonView);
        AppearanceManager.setHapticsEnabled(this, isChecked);
        updateExperienceEnabledState();
    }

    private void onBlurToggled(CompoundButton buttonView, boolean isChecked) {
        if (binding) {
            return;
        }
        HapticsManager.toggle(buttonView);
        AppearanceManager.setBlurEnabled(this, isChecked);
    }

    /** 图标底托开关：只重新染色现有行，不重建页面，避免丢失滚动位置。 */
    private void onIconTilesToggled(CompoundButton buttonView, boolean isChecked) {
        if (binding) {
            return;
        }
        HapticsManager.toggle(buttonView);
        AppearanceManager.setIconTilesEnabled(this, isChecked);
        refreshTiles(findViewById(android.R.id.content));
    }

    private void refreshTiles(View view) {
        if (view instanceof Md3ListItemView) {
            ((Md3ListItemView) view).refreshLeadingTile();
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                refreshTiles(group.getChildAt(i));
            }
        }
    }

    /** 联动状态：振动关闭时强度不可选；系统不支持模糊时开关置灰并说明原因。 */
    private void updateExperienceEnabledState() {
        boolean hapticsOn = switchHaptics != null && switchHaptics.isChecked();
        SettingsRows.setRowEnabled(rowHapticLevel, hapticsOn);

        boolean blurSupported = AppearanceManager.blurSupported();
        if (switchBlur != null) {
            switchBlur.setEnabled(blurSupported);
        }
        if (tvBlurDesc != null) {
            tvBlurDesc.setText(blurSupported
                    ? R.string.appearance_blur_desc : R.string.appearance_blur_unavailable);
        }
    }

    // ------------------------------------------------------------------
    // 取值 / 下标工具
    // ------------------------------------------------------------------

    private String[] paletteKeys() {
        String[] presets = AppearanceManager.PRESET_PALETTES;
        String[] keys = new String[presets.length + 1];
        keys[0] = AppearanceManager.PALETTE_DYNAMIC;
        System.arraycopy(presets, 0, keys, 1, presets.length);
        return keys;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static int indexOfInt(int[] values, int target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfString(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null && values[i].equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfFloat(float[] values, float target) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - target) < 0.01f) {
                return i;
            }
        }
        return -1;
    }

    private void toastApplied() {
        showApplied(getString(R.string.appearance_applied), false);
    }

    /**
     * 统一的「已应用」反馈。
     *
     * @param longDuration 需要用户读完的提示（如动态取色不可用）用长时长
     */
    private void showApplied(CharSequence text, boolean longDuration) {
        // 若紧接着会重建页面，这条提示要由新页面补显示
        pendingAppliedMessage = text;
        if (longDuration) {
            Feedback.showLong(this, text);
        } else {
            Feedback.show(this, text);
        }
        // 没有重建时 1 秒后作废，避免下次进入本页重复提示
        getWindow().getDecorView().postDelayed(() -> pendingAppliedMessage = null, 1000L);
    }

    // ------------------------------------------------------------------
    // 重建与滚动位置
    // ------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (scrollView != null) {
            outState.putInt(STATE_SCROLL_Y, scrollView.getScrollY());
        }
    }

    /** 调色板 / 字号 / 密度改动都会重建本页，这里把滚动位置还原回去。 */
    private void restoreScroll(@Nullable Bundle state) {
        if (state == null || scrollView == null) {
            return;
        }
        final int y = state.getInt(STATE_SCROLL_Y, 0);
        if (y <= 0) {
            return;
        }
        scrollView.post(() -> scrollView.scrollTo(0, y));
    }

    /** 延后到当前事件循环重建，避免在滑杆 / 弹窗回调里同步重建造成窗口抖动。 */
    private void reloadSelf() {
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) {
                recreate();
            }
        });
    }
}
