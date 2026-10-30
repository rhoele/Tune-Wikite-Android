package com.auramusic.wiki.classic;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 未通过原因归档页。
 *
 * <p>入口不在底栏：从「设置 → 账号与提醒 → 未通过原因」进入。
 * 页面先按项目类型（专辑补充 / 纠错 · 专辑、单曲、艺人 / 歌词 / 翻译）分节，
 * 分节标题右侧可以整节展开收起；每节内再按具体原因分组，卡片可单独展开查看条目。
 * 单击原因或条目只复制原因文本，长按条目跳转到「我的贡献」对应卡片。
 */
public class RejectReasonsActivity extends BaseActivity {

    private static final String TAG = "RejectReasons";

    /** 展开 / 收起动画的时长区间（毫秒），按内容高度插值，保证长短列表的观感一致 */
    private static final long MIN_ANIM_MS = 200L;
    private static final long MAX_ANIM_MS = 700L;
    /** 高度动画的目标速度：每秒推进 2600dp */
    private static final int ANIM_DP_PER_SECOND = 2600;

    /** 原因图标底托配色（M3 容器色 + 对应前景色），按原因稳定轮换 */
    private static final int[] TILE_BG_ATTR = {
            com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorTertiaryContainer,
            com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
    };
    private static final int[] TILE_FG_ATTR = {
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            com.google.android.material.R.attr.colorOnTertiaryContainer,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
    };
    /** 语义图标撞车 / 无法识别时的备选池，保证相邻两组不会重复 */
    private static final int[] TILE_FALLBACK_ICONS = {
            R.drawable.ic_report, R.drawable.ic_info, R.drawable.ic_category,
            R.drawable.ic_fact_check, R.drawable.ic_rule, R.drawable.ic_label,
            R.drawable.ic_bookmark, R.drawable.ic_priority_high,
    };

    /** 展开中的原因分组（按原因文本标识），重新渲染 / 同步后保持 */
    private final Set<String> expandedKeys = new HashSet<>();
    /** 已展开的项目类型分节（不记录 = 默认收起） */
    private final Set<Integer> expandedCategories = new HashSet<>();

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private LinearLayout list;
    private TextView summary;
    private View empty;
    private MaterialButton syncButton;
    private boolean syncing;
    /** 空态下同步按钮的呼吸提示（引导用户注意右上角的扫描入口） */
    private ValueAnimator syncHint;
    private ObjectAnimator syncSpin;
    /** 上一次渲染的数据指纹：数据没变时不再重建整页 */
    private String renderedSignature;
    /** 进入页面时的「已看到这里」时间点：晚于它的条目算新 */
    private long seenAt;
    /** 上一次用过的图标 / 配色索引，用于避免相邻两组重复 */
    private int lastIconRes;
    private int lastTileIndex = -1;

    /**
     * 每轮渲染前一次性解析的主题色。
     *
     * <p>{@code MaterialColors.getColor} 每次都要走一遍主题解析，38 张卡片 × 7 个属性
     * 累积起来是上百次解析；集中取一次可以明显缩短进入页面的时间。
     */
    private int cardColor;
    private int sectionColor;
    private int sectionTitleColor;
    private int sectionMetaColor;
    private int sectionChevronBgColor;
    private int sectionChevronFgColor;
    private int titleColor;
    private int countColor;
    private int mutedColor;
    private int chevronBgColor;
    private int chevronFgColor;
    private int hintColor;
    /** 「彩色图标底托」偏好：关闭后所有图标底托退回中性色 */
    private boolean coloredTiles;
    private final int[] tileBgColors = new int[TILE_BG_ATTR.length];
    private final int[] tileFgColors = new int[TILE_FG_ATTR.length];

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reject_reasons);
        bindToolbar(R.string.reject_archive_title);

        list = findViewById(R.id.list_reject_groups);
        summary = findViewById(R.id.tv_reject_summary);
        empty = findViewById(R.id.layout_reject_empty);
        syncButton = findViewById(R.id.btn_reject_sync);
        syncButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            sync(false);
        });
        // 长按才做整段会话的回填：日常只是补最新几条，没必要每次翻完整个历史
        syncButton.setOnLongClickListener(v -> {
            HapticsManager.longPress(v);
            Feedback.show(this, R.string.reject_archive_sync_full_hint);
            sync(true);
            return true;
        });

        // 汇总卡与空态的图标同样是「底托」，需要跟随外观偏好
        applyStaticIconTiles();

        long started = android.os.SystemClock.uptimeMillis();
        // 首次使用该功能时把「已看到这里」初始化到现在，避免把历史记录全部标成新
        seenAt = RejectArchive.seenAt(this);
        if (seenAt <= 0) {
            seenAt = System.currentTimeMillis();
            RejectArchive.markSeen(this);
        }
        render();
        android.util.Log.d(TAG, "首屏渲染 " + (android.os.SystemClock.uptimeMillis() - started)
                + "ms 卡片=" + list.getChildCount());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面即视为「都看过了」：下次进来 +N 与高亮都会消失
        RejectArchive.markSeen(this);
        stopSyncHint();
    }

    /**
     * 汇总卡 / 空态的静态图标底托。
     *
     * <p>布局里写死的是 tonal 圆形底托，关闭「彩色图标底托」后要一并退回中性色，
     * 否则会出现「列表行的底托关了、这两处还亮着」的不一致。
     */
    private void applyStaticIconTiles() {
        boolean colored = AppearanceManager.iconTilesEnabled(this);
        int[] ids = {R.id.iv_reject_header_icon, R.id.iv_reject_empty_icon};
        for (int id : ids) {
            ImageView icon = findViewById(id);
            if (icon == null) {
                continue;
            }
            if (colored) {
                icon.setBackgroundResource(R.drawable.bg_icon_circle);
                icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon,
                        com.google.android.material.R.attr.colorOnSecondaryContainer)));
            } else {
                icon.setBackground(null);
                icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(icon,
                        com.google.android.material.R.attr.colorOnSurfaceVariant)));
            }
        }
    }

    /** 空态引导：同步按钮以 2 秒为周期做很轻的呼吸缩放。 */
    private void startSyncHint() {
        if (syncHint != null || syncButton == null) {
            return;
        }
        if (!MotionManager.animationsEnabled(this)) {
            return;
        }
        syncHint = ValueAnimator.ofFloat(0f, 1f);
        syncHint.setDuration(2000L);
        syncHint.setRepeatCount(ValueAnimator.INFINITE);
        syncHint.setInterpolator(new LinearInterpolator());
        syncHint.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // sin(0..π)：从原位轻微放大再回到原位，1 个周期只有一次呼吸
            float scale = 1f + 0.05f * (float) Math.sin(t * Math.PI);
            syncButton.setScaleX(scale);
            syncButton.setScaleY(scale);
        });
        syncHint.start();
    }

    private void stopSyncHint() {
        if (syncHint != null) {
            syncHint.cancel();
            syncHint = null;
        }
        if (syncButton != null) {
            syncButton.setScaleX(1f);
            syncButton.setScaleY(1f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        if (syncing || !SessionManager.isLoggedIn(this)) {
            return;
        }
        // 尚未做过全量回填时自动补一次：此前只有「我的贡献」刷新才会间接触发，
        // 且一次只取最新一页，页面会长期停在「只有最近几条」的状态。
        if (!RejectArchive.fullBackfillDone(this)) {
            sync(true);
        } else if (RejectArchive.syncDue(this)) {
            // 已经回填过：进入页面时静默补一次最新私信，不再等用户手动点同步
            sync(false, true);
        }
    }

    @Override
    protected void onDestroy() {
        stopSyncSpin();
        stopSyncHint();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render() {
        List<RejectArchive.Group> groups = RejectArchive.groups(this);
        int total = 0;
        int newTotal = 0;
        StringBuilder signature = new StringBuilder();
        for (RejectArchive.Group group : groups) {
            int newCount = newCount(group);
            total += group.items.size();
            newTotal += newCount;
            signature.append(group.category).append('|').append(group.reason)
                    .append('#').append(group.items.size()).append('+').append(newCount).append(';');
        }
        summary.setText(newTotal > 0
                ? getString(R.string.reject_archive_summary_new, total, groups.size(), newTotal)
                : getString(R.string.reject_archive_summary, total, groups.size()));
        summary.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
        empty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        // 空态时让同步按钮轻微「呼吸」，把视线引到右上角的扫描入口
        if (total == 0) {
            startSyncHint();
        } else {
            stopSyncHint();
        }

        // 数据没变（例如 onCreate 之后紧接着 onResume）就不重建视图，
        // 既省掉整页构建，也不会打断用户已经展开的分组与滚动位置。
        final String current = signature.toString();
        if (list.getChildCount() > 0 && current.equals(renderedSignature)) {
            return;
        }
        renderedSignature = current;

        list.removeAllViews();
        lastIconRes = 0;
        lastTileIndex = -1;
        resolveColors();
        // 全部按收起状态渲染：之前「首屏自动展开第一条」会随数据落到不同类别上，
        // 看起来像某个类别的第一条原因被固定展开，且用户收起后下次进来又出现
        // 先按项目类型分节：标题栏显示类别，右侧控件整节展开 / 收起
        java.util.Map<Integer, java.util.List<RejectArchive.Group>> sections =
                new java.util.LinkedHashMap<>();
        for (RejectArchive.Group group : groups) {
            sections.computeIfAbsent(group.category, key -> new java.util.ArrayList<>()).add(group);
        }
        for (java.util.Map.Entry<Integer, java.util.List<RejectArchive.Group>> entry
                : sections.entrySet()) {
            list.addView(categorySection(entry.getKey(), entry.getValue()));
        }
        if (total > 0) {
            list.addView(hintText());
        }
    }

    /** 分组唯一键：同一句原因可能分别出现在「纠错 · 艺人」和「纠错 · 专辑」下。 */
    private static String keyOf(RejectArchive.Group group) {
        return group.category + "|" + group.reason;
    }

    /** 组内晚于上次浏览时间的条目数（用于 +N 与高亮）。 */
    private int newCount(RejectArchive.Group group) {
        int count = 0;
        for (RejectArchive.Entry entry : group.items) {
            if (entry != null && entry.time > seenAt) {
                count++;
            }
        }
        return count;
    }

    /**
     * 一个项目类型分节：标题栏（类别 + 条数 / 原因类数 + 新增角标 + 展开收起控件）+ 原因卡片。
     *
     * <p>类别只在这里出现，卡片内部不再重复；标题栏右侧的控件负责整节展开 / 收起。
     */
    private View categorySection(int category, List<RejectArchive.Group> groups) {
        final boolean expanded = expandedCategories.contains(category);
        int inset = getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        int gap = getResources().getDimensionPixelSize(R.dimen.list_item_spacing);

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dp(6);
        section.setLayoutParams(sectionLp);

        MaterialCardView headerCard = new MaterialCardView(this);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = gap;
        headerCard.setLayoutParams(headerLp);
        headerCard.setCardBackgroundColor(sectionColor);
        headerCard.setRadius(dp(18));
        headerCard.setCardElevation(0f);
        headerCard.setStrokeWidth(0);
        headerCard.setClipToOutline(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(ripple());
        header.setMinimumHeight(dp(56));
        header.setPadding(inset, dp(8), dp(4), dp(8));
        headerCard.addView(header);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        header.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(categoryLabel(category));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        title.setTextColor(sectionTitleColor);
        texts.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(2);
        texts.addView(metaRow, metaLp);

        int total = 0;
        int newTotal = 0;
        for (RejectArchive.Group group : groups) {
            total += group.items.size();
            newTotal += newCount(group);
        }
        TextView count = new TextView(this);
        count.setText(getString(R.string.reject_category_section_count, total, groups.size()));
        count.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        count.setTextColor(sectionMetaColor);
        metaRow.addView(count);

        if (newTotal > 0) {
            TextView badge = new TextView(this);
            badge.setText(getString(R.string.reject_archive_new_count, newTotal));
            badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            badge.setTextColor(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorOnPrimaryContainer));
            badge.setBackgroundResource(R.drawable.bg_new_badge);
            badge.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorPrimaryContainer)));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(dp(6));
            metaRow.addView(badge, badgeLp);
        }

        ImageButton chevron = new ImageButton(this);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setBackgroundResource(R.drawable.bg_icon_circle);
        chevron.setBackgroundTintList(ColorStateList.valueOf(sectionChevronBgColor));
        chevron.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chevron.setPadding(dp(9), dp(9), dp(9), dp(9));
        chevron.setImageTintList(ColorStateList.valueOf(sectionChevronFgColor));
        chevron.setRotation(expanded ? 180f : 0f);
        chevron.setContentDescription(getString(expanded
                ? R.string.reject_archive_collapse : R.string.reject_archive_expand));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        chevronLp.setMarginStart(dp(4));
        chevronLp.setMarginEnd(dp(12));
        header.addView(chevron, chevronLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        for (RejectArchive.Group group : groups) {
            body.addView(groupCard(group));
        }
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);

        final Runnable toggle = () -> toggleSection(category, body, chevron);
        header.setOnClickListener(v -> {
            HapticsManager.toggle(header);
            toggle.run();
        });
        chevron.setOnClickListener(v -> {
            HapticsManager.toggle(chevron);
            toggle.run();
        });

        section.addView(headerCard);
        section.addView(body);
        return section;
    }

    /** 整节展开 / 收起：与单个原因卡片使用同一套高度补间与箭头旋转。 */
    private void toggleSection(int category, LinearLayout body, View chevron) {
        final boolean expand = body.getVisibility() != View.VISIBLE;
        if (expand) {
            expandedCategories.add(category);
        } else {
            expandedCategories.remove(category);
        }
        chevron.setContentDescription(getString(expand
                ? R.string.reject_archive_collapse : R.string.reject_archive_expand));
        if (expand) {
            body.setVisibility(View.VISIBLE);
            int width = body.getWidth() > 0 ? body.getWidth() : Math.max(0, list.getWidth());
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int visible = Math.max(dp(1),
                    Math.min(body.getMeasuredHeight(), viewportHeight()));
            final long duration = durationFor(visible);
            chevron.animate().rotation(180f).setDuration(duration)
                    .setInterpolator(MotionManager.standard(this)).start();
            animateHeight(body, 0, visible, true, duration);
        } else {
            final int from = Math.max(dp(1), Math.min(body.getHeight(), viewportHeight()));
            ViewGroup.LayoutParams lp = body.getLayoutParams();
            lp.height = from;
            body.setLayoutParams(lp);
            final long duration = durationFor(from);
            chevron.animate().rotation(0f).setDuration(duration)
                    .setInterpolator(MotionManager.standard(this)).start();
            animateHeight(body, from, 0, false, duration);
        }
    }

    /**
     * 一个原因分组：头部（图标 + 原因 + 计数 + 复制 + 展开箭头）+ 可收起的专辑列表。
     *
     * <p>卡片圆角 / 间距与设置页的独立卡片一致（20dp + 8dp），头部最小 64dp 保证热区。
     */
    private View groupCard(RejectArchive.Group group) {
        int inset = getResources().getDimensionPixelSize(R.dimen.settings_row_inset);
        int gap = getResources().getDimensionPixelSize(R.dimen.list_item_spacing);
        final boolean expanded = expandedKeys.contains(keyOf(group));

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = gap;
        card.setLayoutParams(cardLp);
        card.setCardBackgroundColor(cardColor);
        card.setRadius(dp(20));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        card.setClipToOutline(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        card.addView(box);

        // ---- 头部：整行可点，单击展开 / 收起 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(ripple());
        header.setMinimumHeight(dp(64));
        header.setPadding(inset, dp(10), dp(4), dp(10));

        int tileIndex = nextTileIndex(group.reason);
        ImageView tile = new ImageView(this);
        tile.setImageResource(iconFor(group.reason));
        if (coloredTiles) {
            tile.setBackgroundResource(R.drawable.bg_icon_circle);
            tile.setBackgroundTintList(ColorStateList.valueOf(tileBgColors[tileIndex]));
            tile.setImageTintList(ColorStateList.valueOf(tileFgColors[tileIndex]));
        } else {
            // 关闭「彩色图标底托」后不再画色块，退回中性线图标
            tile.setBackground(null);
            tile.setImageTintList(ColorStateList.valueOf(mutedColor));
        }
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.addView(tile, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.setMarginStart(dp(12));
        header.addView(texts, textsLp);

        TextView title = new TextView(this);
        title.setText(group.reason);
        // 原因文案要完整可读：不截断、不限行数（长原因会自然换行）
        title.setMaxLines(Integer.MAX_VALUE);
        title.setEllipsize(null);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        title.setTextColor(titleColor);
        texts.addView(title);

        // 计数行：「96 条通知」+ 新增角标「+2」
        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams countRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countRowLp.topMargin = dp(2);
        texts.addView(countRow, countRowLp);

        TextView count = new TextView(this);
        count.setText(getString(R.string.reject_archive_group_entries, group.items.size()));
        count.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        count.setTextColor(mutedColor);
        countRow.addView(count);

        final int newCount = newCount(group);
        if (newCount > 0) {
            TextView badge = new TextView(this);
            badge.setText(getString(R.string.reject_archive_new_count, newCount));
            badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            badge.setTextColor(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorOnPrimaryContainer));
            badge.setBackgroundResource(R.drawable.bg_new_badge);
            badge.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorPrimaryContainer)));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(dp(6));
            countRow.addView(badge, badgeLp);
        }

        // 复制按钮：与展开箭头分开，避免「想展开却复制了」
        ImageButton copy = new ImageButton(this);
        copy.setImageResource(R.drawable.ic_wiki_copy);
        copy.setBackgroundResource(selectableBackground());
        copy.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        copy.setPadding(dp(12), dp(12), dp(12), dp(12));
        copy.setImageTintList(ColorStateList.valueOf(mutedColor));
        copy.setContentDescription(getString(R.string.reject_archive_copy_reason_action));
        header.addView(copy, new LinearLayout.LayoutParams(dp(48), dp(48)));

        // 展开箭头：tonal 圆形底托，展开时整体旋转 180°（Expressive 的展开语义）
        ImageButton chevron = new ImageButton(this);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setBackgroundResource(R.drawable.bg_icon_circle);
        chevron.setBackgroundTintList(ColorStateList.valueOf(chevronBgColor));
        chevron.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chevron.setPadding(dp(9), dp(9), dp(9), dp(9));
        chevron.setImageTintList(ColorStateList.valueOf(chevronFgColor));
        chevron.setRotation(expanded ? 180f : 0f);
        chevron.setContentDescription(getString(expanded
                ? R.string.reject_archive_collapse : R.string.reject_archive_expand));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        chevronLp.setMarginStart(dp(4));
        chevronLp.setMarginEnd(dp(12));
        header.addView(chevron, chevronLp);

        box.addView(header);

        // ---- 主体：专辑明细（首次展开时才构建，避免进入页面就创建上百行） ----
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(inset, 0, inset, dp(8));
        if (expanded) {
            fillBody(body, group);
        }
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        box.addView(body);

        final Runnable toggle = () -> toggleBody(group, body, chevron);
        header.setOnClickListener(v -> {
            HapticsManager.toggle(header);
            toggle.run();
        });
        chevron.setOnClickListener(v -> {
            HapticsManager.toggle(chevron);
            toggle.run();
        });
        copy.setOnClickListener(v -> {
            // 卡片复制「完整全文」：保留通知句式，只把专辑名清空为《》
            String raw = group.items.isEmpty() ? "" : group.items.get(0).raw;
            String text = RejectArchive.rawWithoutAlbum(raw);
            if (text.isEmpty()) {
                text = group.reason;
            }
            copy(text, R.string.reject_archive_copy_full, v);
            HapticsManager.confirm(v);
        });
        return card;
    }

    /** 明细行只在第一次展开时创建，之后直接复用已建好的子视图。 */
    private void fillBody(LinearLayout body, RejectArchive.Group group) {
        if (Boolean.TRUE.equals(body.getTag())) {
            return;
        }
        body.setTag(Boolean.TRUE);
        for (RejectArchive.Entry entry : group.items) {
            body.addView(itemRow(entry, entry != null && entry.time > seenAt));
        }
    }

    /** 项目类型文案 */
    private String categoryLabel(int category) {
        switch (category) {
            case RejectArchive.CAT_ALBUM:
                return getString(R.string.reject_cat_album);
            case RejectArchive.CAT_FIX_ALBUM:
                return getString(R.string.reject_cat_fix_album);
            case RejectArchive.CAT_FIX_SONG:
                return getString(R.string.reject_cat_fix_song);
            case RejectArchive.CAT_FIX_ARTIST:
                return getString(R.string.reject_cat_fix_artist);
            case RejectArchive.CAT_FIX:
                return getString(R.string.reject_cat_fix);
            case RejectArchive.CAT_FIX_LYRIC:
                return getString(R.string.reject_cat_fix_lyric);
            case RejectArchive.CAT_FIX_TRANSLATION:
                return getString(R.string.reject_cat_fix_translation);
            case RejectArchive.CAT_LYRIC:
                return getString(R.string.reject_cat_lyric);
            case RejectArchive.CAT_TRANSLATION:
                return getString(R.string.reject_cat_translation);
            default:
                return getString(R.string.reject_cat_other);
        }
    }

    /**
     * 展开 / 收起：高度动画 + 箭头旋转，两者共用同一时长。
     *
     * <p>只补间「屏幕可见的那一段高度」：几十行的大组全量撑开有好几屏，
     * 固定 260ms 会让它在几帧里闪完，和小组的观感完全不同；而按可见高度补间，
     * 大小组的展开速度、曲线、箭头节奏都保持一致，视口之外的部分在动画结束时
     * 一次性补全（本来也看不见）。
     */
    private void toggleBody(RejectArchive.Group group, LinearLayout body, View chevron) {
        final String reason = keyOf(group);
        final boolean expand = body.getVisibility() != View.VISIBLE;
        if (expand) {
            expandedKeys.add(reason);
        } else {
            expandedKeys.remove(reason);
        }
        chevron.setContentDescription(getString(expand
                ? R.string.reject_archive_collapse : R.string.reject_archive_expand));

        if (expand) {
            fillBody(body, group);
            body.setVisibility(View.VISIBLE);
            int width = body.getWidth() > 0 ? body.getWidth()
                    : Math.max(0, list.getWidth() - dp(40));
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int visible = Math.max(dp(1),
                    Math.min(body.getMeasuredHeight(), viewportHeight()));
            final long duration = durationFor(visible);
            chevron.animate().rotation(180f).setDuration(duration)
                    .setInterpolator(MotionManager.standard(this)).start();
            animateHeight(body, 0, visible, true, duration);
        } else {
            // 先把超出可见范围的部分直接裁掉（在屏幕外，看不出来），再按可见高度收起
            final int from = Math.max(dp(1), Math.min(body.getHeight(), viewportHeight()));
            ViewGroup.LayoutParams lp = body.getLayoutParams();
            lp.height = from;
            body.setLayoutParams(lp);
            final long duration = durationFor(from);
            chevron.animate().rotation(0f).setDuration(duration)
                    .setInterpolator(MotionManager.standard(this)).start();
            animateHeight(body, from, 0, false, duration);
        }
    }

    /** 列表可视区高度（用于把展开动画限制在一屏之内）。 */
    private int viewportHeight() {
        View node = list;
        for (int i = 0; i < 3 && node != null; i++) {
            View parent = node.getParent() instanceof View ? (View) node.getParent() : null;
            if (parent == null) {
                break;
            }
            if (parent instanceof androidx.core.widget.NestedScrollView) {
                int height = parent.getHeight();
                if (height > 0) {
                    return height;
                }
            }
            node = parent;
        }
        int fallback = getResources().getDisplayMetrics().heightPixels;
        return fallback > 0 ? fallback : dp(640);
    }

    /** 按高度折算时长：200ms 起，最长 700ms，长短列表的推进速度一致。 */
    private long durationFor(int heightPx) {
        long travel = Math.max(0, heightPx) * 1000L / Math.max(1, dp(ANIM_DP_PER_SECOND));
        return Math.min(MAX_ANIM_MS, Math.max(MIN_ANIM_MS, MIN_ANIM_MS + travel));
    }

    // ------------------------------------------------------------------
    // 图标与配色
    // ------------------------------------------------------------------

    /**
     * 一次性解析本轮渲染需要的主题色。
     *
     * <p>卡片数量固定且会随同步增加，逐卡解析主题色在低端机上会拖慢进入页面。
     */
    private void resolveColors() {
        View anchor = list != null ? list : getWindow().getDecorView();
        coloredTiles = AppearanceManager.iconTilesEnabled(anchor.getContext());
        cardColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        // 项目类型分节用 tonal 容器，和原因卡片的中性 surface 明确分层
        sectionColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorSecondaryContainer);
        sectionTitleColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnSecondaryContainer);
        sectionMetaColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
                MaterialColors.getColor(anchor,
                        com.google.android.material.R.attr.colorOnSecondaryContainer), 0xBF);
        // 分节标题本身是 tonal 底，圆形按钮改用中性容器才不会与背景融合
        sectionChevronBgColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorSurfaceContainerHighest);
        sectionChevronFgColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        titleColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnSurface);
        countColor = MaterialColors.getColor(anchor, androidx.appcompat.R.attr.colorPrimary);
        mutedColor = MaterialColors.getColor(anchor,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        // 展开箭头同样属于「图标底托」：关闭彩色底托后退回中性容器
        chevronBgColor = MaterialColors.getColor(anchor, coloredTiles
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHighest);
        chevronFgColor = MaterialColors.getColor(anchor, coloredTiles
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant);
        hintColor = mutedColor;
        for (int i = 0; i < TILE_BG_ATTR.length; i++) {
            tileBgColors[i] = MaterialColors.getColor(anchor, TILE_BG_ATTR[i]);
            tileFgColors[i] = MaterialColors.getColor(anchor, TILE_FG_ATTR[i]);
        }
    }

    /** 原因图标：先按语义取，撞上相邻一组的图标时换一个备选，降低重复率。 */
    private int iconFor(String reason) {
        int icon = semanticIcon(reason);
        if (icon == 0) {
            // 新出现的、没有命中任何关键词的原因：按文案哈希稳定落在一个通用图标上，
            // 既不会所有新原因都挤在同一个图标，重进页面也保持一致
            int count = TILE_FALLBACK_ICONS.length;
            int index = (reason == null ? 0 : (reason.hashCode() & 0x7fffffff)) % count;
            icon = TILE_FALLBACK_ICONS[index];
        }
        if (icon == lastIconRes) {
            for (int candidate : TILE_FALLBACK_ICONS) {
                if (candidate != icon) {
                    icon = candidate;
                    break;
                }
            }
        }
        lastIconRes = icon;
        return icon;
    }

    /** 按关键词判断语义图标；无法判断时返回 0，交给哈希备选池。 */
    private static int semanticIcon(String reason) {
        if (reason == null || reason.isEmpty()) {
            return 0;
        }
        if (reason.contains("版权")) {
            return R.drawable.ic_copyright;
        }
        if (reason.contains("歌手") || reason.contains("艺人")
                || reason.contains("非个人") || reason.contains("音乐人")) {
            return R.drawable.ic_person_alert;
        }
        if (reason.contains("非正式") || reason.contains("发行")) {
            return R.drawable.ic_unpublished;
        }
        if (reason.contains("翻译")) {
            return R.drawable.ic_translate;
        }
        if (reason.contains("歌词") || reason.contains("时间轴")) {
            return R.drawable.ic_lyrics;
        }
        if (reason.contains("封面") || reason.contains("图片")) {
            return R.drawable.ic_broken_image;
        }
        if (reason.contains("链接")) {
            return R.drawable.ic_link_off;
        }
        if (reason.contains("不存在") || reason.contains("无法找到") || reason.contains("找不到")) {
            return R.drawable.ic_search_off;
        }
        if (reason.contains("重复") || reason.contains("已在曲库中")) {
            return R.drawable.ic_content_copy;
        }
        if (reason.contains("不一致") || reason.contains("不符")) {
            return R.drawable.ic_difference;
        }
        if (reason.contains("来源") || reason.contains("参考")) {
            return R.drawable.ic_plagiarism;
        }
        if (reason.contains("资料")) {
            return R.drawable.ic_fact_check;
        }
        if (reason.contains("歌曲") || reason.contains("音频")) {
            return R.drawable.ic_music_note;
        }
        if (reason.contains("专辑")) {
            return R.drawable.ic_album;
        }
        if (reason.contains("传输") || reason.contains("下载") || reason.contains("网络")) {
            return R.drawable.ic_cloud_off;
        }
        if (reason.contains("规则") || reason.contains("规定") || reason.contains("违规")) {
            return R.drawable.ic_rule;
        }
        return 0;
    }

    /**
     * 底托配色索引：按原因文本稳定取色（同一原因每次进来颜色一致），
     * 并避开上一组刚用过的颜色，让长列表的颜色节奏不呆板。
     */
    private int nextTileIndex(String reason) {
        int count = TILE_BG_ATTR.length;
        int index = (reason == null ? 0 : (reason.hashCode() & 0x7fffffff)) % count;
        if (index == lastTileIndex) {
            index = (index + 1) % count;
        }
        lastTileIndex = index;
        return index;
    }

    private void animateHeight(final View view, int from, int to, boolean expand, long duration) {
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(duration);
        animator.setInterpolator(expand ? MotionManager.emphasized(this)
                : MotionManager.standard(this));
        animator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            lp.height = (int) animation.getAnimatedValue();
            view.setLayoutParams(lp);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(lp);
                if (!expand) {
                    view.setVisibility(View.GONE);
                }
            }
        });
        animator.start();
    }

    private View itemRow(RejectArchive.Entry entry, boolean isNew) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple());
        row.setPadding(0, dp(11), 0, dp(11));
        row.setMinimumHeight(dp(44));

        TextView album = new TextView(this);
        album.setText(RejectArchive.displayName(entry));
        album.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        album.setTextColor(isNew ? countColor : titleColor);
        album.setMaxLines(2);
        row.addView(album, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isNew) {
            TextView badge = new TextView(this);
            badge.setText(R.string.reject_archive_new_badge);
            badge.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
            badge.setTextColor(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorOnPrimaryContainer));
            badge.setBackgroundResource(R.drawable.bg_new_badge);
            badge.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(badge,
                    com.google.android.material.R.attr.colorPrimaryContainer)));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(dp(8));
            row.addView(badge, badgeLp);
        }

        TextView date = new TextView(this);
        date.setText(entry.time > 0 ? dateFormat.format(new Date(entry.time)) : "");
        date.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        date.setTextColor(mutedColor);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.setMarginStart(dp(12));
        row.addView(date, dateLp);

        row.setOnClickListener(v -> {
            // 条目复制这条通知的专属原文（带具体专辑名，不做任何删改）
            copy(entry.raw, R.string.reject_archive_copy_raw, v);
            HapticsManager.tap(v);
        });
        // 长按：回到「我的贡献」并定位到这条通知对应的卡片
        row.setOnLongClickListener(v -> {
            HapticsManager.longPress(v);
            openInContributions(entry);
            return true;
        });
        return row;
    }

    /** 长按条目：跳回「我的贡献」，由主界面按项目类型定位到对应卡片。 */
    private void openInContributions(RejectArchive.Entry entry) {
        String target = RejectArchive.jumpTarget(entry);
        if (target.isEmpty()) {
            Feedback.show(this, R.string.reject_archive_jump_no_album);
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_FOCUS_ALBUM, target);
        intent.putExtra(MainActivity.EXTRA_FOCUS_TIME, entry.time);
        intent.putExtra(MainActivity.EXTRA_FOCUS_CATEGORY, entry.category);
        // 回到已有的主界面实例，不新建一层
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private TextView hintText() {
        TextView hint = new TextView(this);
        hint.setText(R.string.reject_archive_copy_hint);
        hint.setTextAppearance(R.style.TextAppearance_ClassicWiki_Supporting);
        hint.setTextColor(hintColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        hint.setLayoutParams(lp);
        return hint;
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    private void copy(String text, int toastRes, View anchor) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.reject_archive_title), text));
        }
        HapticsManager.confirm(anchor);
        Feedback.show(this, toastRes);
    }

    /**
     * 同步审核私信。
     *
     * @param fullHistory {@code true} 翻完整个会话（首次回填 / 长按）；{@code false} 只取最新一页
     */
    private void sync(boolean fullHistory) {
        sync(fullHistory, false);
    }

    /**
     * 同步审核私信。
     *
     * @param fullHistory {@code true} 翻完整个会话（首次回填 / 长按）；{@code false} 只取最新一页
     * @param silent      自动同步：失败与「没有新内容」都不打扰用户，只有真的补到条目才提示
     */
    private void sync(boolean fullHistory, boolean silent) {
        if (syncing) {
            return;
        }
        if (!SessionManager.isLoggedIn(this)) {
            Feedback.show(this, R.string.reject_archive_need_login);
            return;
        }
        syncing = true;
        syncButton.setEnabled(false);
        // 同步中由旋转动画承担反馈，呼吸提示先停下，避免两个动画叠加
        stopSyncHint();
        startSyncSpin();
        final int before = RejectArchive.entries(this).size();
        RejectArchive.syncNow(this, fullHistory, (changed, error) -> {
            syncing = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            syncButton.setEnabled(true);
            stopSyncSpin();
            render();
            if (error != null) {
                // 失败原因要显式提示：静默失败会让空态看起来像「确实没有未通过记录」
                if (!silent) {
                    Feedback.show(this, getString(R.string.reject_archive_sync_failed, error));
                }
                return;
            }
            int added = RejectArchive.entries(this).size() - before;
            if (added > 0) {
                Feedback.show(this, getString(R.string.reject_archive_synced, added));
            } else if (!silent) {
                Feedback.show(this, R.string.reject_archive_no_new);
            }
        });
    }

    /** 同步中让图标匀速旋转，替代原来的「按钮文字变成正在同步…」。 */
    private void startSyncSpin() {
        stopSyncSpin();
        syncSpin = ObjectAnimator.ofFloat(syncButton, View.ROTATION, 0f, 360f);
        syncSpin.setDuration(900L);
        syncSpin.setRepeatCount(ValueAnimator.INFINITE);
        syncSpin.setInterpolator(new LinearInterpolator());
        syncSpin.start();
    }

    private void stopSyncSpin() {
        if (syncSpin != null) {
            syncSpin.cancel();
            syncSpin = null;
        }
        if (syncButton != null) {
            syncButton.setRotation(0f);
        }
    }

    // ------------------------------------------------------------------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 行水波纹背景（与设置列表的行反馈一致）。 */
    private int selectableBackground() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private android.graphics.drawable.Drawable ripple() {
        int res = selectableBackground();
        return res == 0 ? null : getDrawable(res);
    }
}
