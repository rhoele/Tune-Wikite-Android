package com.auramusic.wiki.classic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌曲百科查询页（对应基准 F1–F14 的完整主链路）。
 *
 * 设计要点：
 *   - 四态闭环：内容 / 加载 / 空态 / 错误（G5）
 *   - 代际安全：每次查询自增 generation，回调时校验，避免旧请求覆盖新结果（F15）
 *   - 可取消：页面销毁或重新查询时取消在途请求
 *   - 历史持久化与恢复：写入 {@link QueryHistoryStore}，并支持旋转后离线回看（F4/F14）
 */
public class WikiQueryView extends LinearLayout {

    private static final SimpleDateFormat RECORD_TIME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    // 输入区
    private TextInputLayout tilQuery;
    private TextInputEditText etQuery;
    private MaterialButton btnQuery;
    private MaterialButton btnPaste;

    // 状态区
    private View contentScroll;
    @Nullable
    private Runnable contentScrollListener;
    private View stateLoading;
    private View stateEmpty;
    private View stateError;
    private LinearLayout sectionsContainer;
    private TextView tvLoadingText;
    private TextView tvErrorMessage;
    private ChipGroup historyContainer;
    private View historyScroll;
    private TextView tvHistoryTitle;

    // 头部
    private ImageView ivSongCover;
    private TextView tvSongName;
    private TextView tvSongArtist;
    private TextView tvSongId;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private QueryHistoryStore historyStore;

    /** 当前已渲染数据，供复制 JSON / 刷新使用 */
    private SongWiki current;
    /** 当前展示的歌曲 ID */
    private long currentSongId = -1L;
    /** 最后一次执行的原始输入，用于重试 */
    private String lastInput = "";

    private Http.CancelToken inflight;
    private int generation;
    private boolean disposed;

    public WikiQueryView(Context context) {
        this(context, null);
    }

    public WikiQueryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    /**
     * 悬浮底栏占用的底部留白（px）。
     *
     * <p>底栏改为覆盖在内容之上后，滚动内容的最后一段会被底栏压住；这里把留白交给
     * 内部的滚动容器（{@code clipToPadding=false}），保证最后一条能完整滚出底栏之上，
     * 同时内容仍可从半透明底栏下穿过。
     */
    public void setBottomInset(int px) {
        if (contentScroll == null) {
            return;
        }
        contentScroll.setPadding(contentScroll.getPaddingLeft(), contentScroll.getPaddingTop(),
                contentScroll.getPaddingRight(), Math.max(0, px));
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent));
        LayoutInflater.from(context).inflate(R.layout.view_wiki_query, this, true);

        historyStore = new QueryHistoryStore(context);

        tilQuery = findViewById(R.id.til_query);
        etQuery = findViewById(R.id.et_query);
        btnQuery = findViewById(R.id.btn_query);
        btnPaste = findViewById(R.id.btn_paste);

        contentScroll = findViewById(R.id.content_scroll);
        // 内容不足一屏时同样允许超限回弹，与「我的贡献」页保持一致的滚动手感
        contentScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        stateLoading = findViewById(R.id.state_loading);
        stateEmpty = findViewById(R.id.state_empty);
        stateError = findViewById(R.id.state_error);
        sectionsContainer = findViewById(R.id.sections_container);
        tvLoadingText = findViewById(R.id.tv_loading_text);
        tvErrorMessage = findViewById(R.id.tv_error_message);
        historyContainer = findViewById(R.id.history_container);
        historyScroll = findViewById(R.id.history_scroll);
        tvHistoryTitle = findViewById(R.id.tv_history_title);

        ivSongCover = findViewById(R.id.iv_song_cover);
        tvSongName = findViewById(R.id.tv_song_name);
        tvSongArtist = findViewById(R.id.tv_song_artist);
        tvSongId = findViewById(R.id.tv_song_id);
        bindContentScrollListener();

        btnQuery.setOnClickListener(v -> {
            HapticsManager.tap(v);
            submit(etQuery.getText() == null ? "" : etQuery.getText().toString());
        });
        btnPaste.setOnClickListener(v -> {
            HapticsManager.tap(v);
            pasteFromClipboard();
        });
        findViewById(R.id.btn_retry).setOnClickListener(v -> {
            HapticsManager.tap(v);
            submit(lastInput);
        });
        findViewById(R.id.btn_open_song).setOnClickListener(v -> {
            HapticsManager.tap(v);
            openSongPage();
        });
        findViewById(R.id.btn_copy_json).setOnClickListener(v -> {
            HapticsManager.tap(v);
            copyJson();
        });
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            HapticsManager.swipe(v);
            submit(lastInput);
        });
        findViewById(R.id.btn_query_other).setOnClickListener(v -> {
            HapticsManager.tap(v);
            etQuery.setText("");
            showEmptyState();
        });

        // 键盘搜索键直接触发查询（F17 键盘交互）
        etQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submit(etQuery.getText() == null ? "" : etQuery.getText().toString());
                return true;
            }
            return false;
        });

        renderHistory();
        showEmptyState();
    }

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    /** 供外部（如通知点击、外部 Intent）直接发起查询 */
    public void queryFromInput(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return;
        }
        etQuery.setText(rawInput.trim());
        submit(rawInput);
    }

    /** 页面主滚动容器（供底栏模糊跟随超限位移）。 */
    @Nullable
    androidx.core.widget.NestedScrollView mainScroll() {
        return contentScroll instanceof androidx.core.widget.NestedScrollView
                ? (androidx.core.widget.NestedScrollView) contentScroll : null;
    }

    /** 设置内容滚动监听。 */
    public void setOnContentScrollListener(@Nullable Runnable listener) {
        contentScrollListener = listener;
        bindContentScrollListener();
    }

    /** 恢复上次查询（启动时调用）；返回是否命中了历史 */
    public boolean restoreLastQuery() {
        QueryHistory.Entry last = historyStore.last();
        if (last == null) {
            return false;
        }
        if (last.json != null && !last.json.isEmpty()) {
            try {
                SongWiki w = SongWiki.fromJson(last.json);
                current = w;
                currentSongId = w.songId > 0 ? w.songId : last.id;
                lastInput = last.displayTitle();
                etQuery.setText(lastInput);
                render(w);
                if (contentScroll instanceof androidx.core.widget.NestedScrollView) {
                    final int y = Math.max(0, last.scrollY);
                    ((androidx.core.widget.NestedScrollView) contentScroll).post(
                            () -> ((androidx.core.widget.NestedScrollView) contentScroll).scrollTo(0, y));
                }
                Feedback.show(this, R.string.wiki_restored_last);
                return true;
            } catch (WikiException e) {
                // 历史 JSON 已不可解析，降级为重新发起查询
                etQuery.setText(last.displayTitle());
                submit(last.displayTitle());
                return true;
            }
        }
        // 仅有 ID 的历史：直接重新查询
        etQuery.setText(last.displayTitle());
        submit(last.displayTitle());
        return true;
    }

    /** 页面销毁时调用：取消在途请求并释放线程池（F15） */
    public void dispose() {
        disposed = true;
        generation++;
        cancelInflight();
        executor.shutdownNow();
    }

    /** 保存页面状态（F14）：旋转/重建后无需重新联网即可回看 */
    public void saveState(Bundle out) {
        if (out == null) {
            return;
        }
        out.putString("wiki_input", etQuery.getText() == null ? "" : etQuery.getText().toString());
        out.putLong("wiki_song_id", currentSongId);
        out.putString("wiki_json", current == null ? "" : current.rawJson);
        out.putInt("wiki_scroll_y", currentScrollY());
        out.putInt("wiki_state", currentStateTag);
    }

    /** 恢复页面状态（F14） */
    public void restoreState(Bundle in) {
        if (in == null) {
            return;
        }
        String input = in.getString("wiki_input", "");
        long songId = in.getLong("wiki_song_id", -1L);
        String json = in.getString("wiki_json", "");
        int scrollY = in.getInt("wiki_scroll_y", 0);
        int state = in.getInt("wiki_state", STATE_EMPTY);

        if (!TextUtils.isEmpty(input)) {
            etQuery.setText(input);
            lastInput = input;
        }

        if (state == STATE_CONTENT && !TextUtils.isEmpty(json)) {
            try {
                SongWiki w = SongWiki.fromJson(json);
                current = w;
                currentSongId = songId > 0 ? songId : w.songId;
                render(w);
                if (contentScroll instanceof androidx.core.widget.NestedScrollView) {
                    ((androidx.core.widget.NestedScrollView) contentScroll).post(
                            () -> ((androidx.core.widget.NestedScrollView) contentScroll).scrollTo(0, scrollY));
                }
                return;
            } catch (WikiException ignored) {
                // 回退到空态
            }
        } else if (state == STATE_ERROR) {
            showError(in.getString("wiki_error", getContext().getString(R.string.wiki_failed)));
            return;
        }
        showEmptyState();
    }

    private void bindContentScrollListener() {
        if (!(contentScroll instanceof androidx.core.widget.NestedScrollView)) {
            return;
        }
        ((androidx.core.widget.NestedScrollView) contentScroll).setOnScrollChangeListener(
                (androidx.core.widget.NestedScrollView.OnScrollChangeListener)
                        (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                            if (contentScrollListener != null && scrollY != oldScrollY) {
                                contentScrollListener.run();
                            }
                        });
    }

    // ------------------------------------------------------------------
    // 查询主流程
    // ------------------------------------------------------------------

    private void submit(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        lastInput = input;

        long songId = WikiIdParser.parse(input);
        if (!WikiIdParser.isValid(songId)) {
            showError(getContext().getString(R.string.wiki_id_invalid));
            return;
        }

        cancelInflight();
        final int gen = ++generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;

        showLoading(getContext().getString(R.string.wiki_loading, String.valueOf(songId)));

        executor.execute(() -> {
            try {
                final SongWiki wiki = WikiClient.fetch(songId, token);
                if (disposed || gen != generation) {
                    return;
                }
                post(() -> {
                    if (disposed || gen != generation) {
                        return;
                    }
                    current = wiki;
                    currentSongId = wiki.songId > 0 ? wiki.songId : songId;
                    render(wiki);
                    historyStore.push(new QueryHistory.Entry(input, currentSongId,
                            wiki.rawJson, 0));
                });
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    return;
                }
                if (disposed || gen != generation) {
                    return;
                }
                post(() -> {
                    if (disposed || gen != generation) {
                        return;
                    }
                    showError(describe(e));
                });
            } catch (final Exception e) {
                if (disposed || gen != generation) {
                    return;
                }
                post(() -> showError(getContext().getString(R.string.wiki_failed)));
            }
        });
    }

    private void cancelInflight() {
        if (inflight != null) {
            inflight.cancel();
            inflight = null;
        }
    }

    /** 异常分级 → 用户可理解文案（对应 F18） */
    private String describe(WikiException e) {
        Context ctx = getContext();
        switch (e.getKind()) {
            case TIMEOUT:
                return ctx.getString(R.string.wiki_timeout);
            case NO_NETWORK:
                return ctx.getString(R.string.wiki_no_network);
            case HTTP:
                return ctx.getString(R.string.wiki_http_error, e.getHttpCode());
            case TOO_LARGE:
                return ctx.getString(R.string.wiki_too_large);
            case BAD_JSON:
                return ctx.getString(R.string.wiki_bad_json);
            case EMPTY:
                return ctx.getString(R.string.wiki_not_found);
            case CANCELLED:
                return ctx.getString(R.string.wiki_cancelled);
            default:
                return ctx.getString(R.string.wiki_failed);
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void render(SongWiki w) {
        // 头部
        tvSongName.setText(w.displayName());
        tvSongArtist.setText(w.displayArtists());
        tvSongId.setText(getContext().getString(R.string.field_song_id) + " · "
                + (w.songId > 0 ? String.valueOf(w.songId) : "—"));
        if (CoverLoader.isAllowed(w.coverUrl)) {
            ivSongCover.setImageResource(R.drawable.ic_album_muted);
            CoverLoader.get().load(ivSongCover, w.coverUrl,
                    getResources().getDimensionPixelSize(R.dimen.wiki_cover_size));
        } else {
            ivSongCover.setTag(null);
            ivSongCover.setImageResource(R.drawable.ic_album_muted);
        }

        sectionsContainer.removeAllViews();

        // ① 歌曲资料
        LinearLayout info = addSection(getContext().getString(R.string.section_song_info), 0);
        addField(info, R.string.field_language,
                firstNonEmpty(w.language, w.lang));
        addField(info, R.string.field_bpm, w.bpm);
        addField(info, R.string.field_duration, w.duration);
        addField(info, R.string.field_release_date, w.releaseDate);
        addField(info, R.string.field_release_version, w.releaseVersion);
        addField(info, R.string.field_cover_type, w.coverType);
        addField(info, R.string.field_trans_name, w.transName);

        // ② 创作信息
        LinearLayout creative = addSection(getContext().getString(R.string.section_creative), 0);
        addField(creative, R.string.field_genres, String.join("、", w.genres));
        addField(creative, R.string.field_moods, String.join("、", w.moods));
        addField(creative, R.string.field_entertainment, String.join("、", w.entertainment));
        addField(creative, R.string.field_locks, String.join("、", w.locks));
        if (w.creators.isEmpty()) {
            addField(creative, R.string.field_creators, "");
        } else {
            for (SongWiki.Creator c : w.creators) {
                StringBuilder names = new StringBuilder();
                for (SongWiki.Person p : c.people) {
                    if (names.length() > 0) {
                        names.append("、");
                    }
                    names.append(TextUtils.isEmpty(p.name) ? getContext()
                            .getString(R.string.unknown_creator) : p.name);
                }
                addClickableField(creative, c.role, names.toString(), () -> {
                    if (!c.people.isEmpty() && !TextUtils.isEmpty(c.people.get(0).artistId)) {
                        WikiLinks.open(getContext(), WikiLinks.artistUrl(c.people.get(0).artistId));
                    }
                }, !c.people.isEmpty() && !TextUtils.isEmpty(c.people.get(0).artistId));
            }
        }

        // ③ 贡献记录
        LinearLayout records = addSection(getContext().getString(
                R.string.section_records, w.records.size()), 0);
        if (w.records.isEmpty()) {
            TextView tv = new TextView(getContext());
            tv.setText(R.string.records_empty);
            applySupporting(tv);
            records.addView(tv);
        } else {
            for (SongWiki.Record r : w.records) {
                addRecordRow(records, r);
            }
        }

        switchState(STATE_CONTENT);
    }

    private void applySupporting(TextView tv) {
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTextColor(com.google.android.material.color.MaterialColors.getColor(tv,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    /** 新增一个区块卡片，返回其内部可放置内容的纵向容器 */
    private LinearLayout addSection(String title, int marginTopDp) {
        Context ctx = getContext();
        MaterialCardView card = new MaterialCardView(ctx);
        LayoutParams cardLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.leftMargin = cardLp.rightMargin =
                getResources().getDimensionPixelSize(R.dimen.screen_horizontal_margin);
        cardLp.topMargin = marginTopDp > 0 ? marginTopDp
                : getResources().getDimensionPixelSize(R.dimen.list_item_spacing);
        card.setLayoutParams(cardLp);
        card.setRadius(getResources().getDimension(R.dimen.card_corner_radius));
        card.setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorSurfaceContainerLow));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.spacing_lg);
        body.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        tvTitle.setTextColor(com.google.android.material.color.MaterialColors.getColor(tvTitle,
                com.google.android.material.R.attr.colorOnSurface));
        body.addView(tvTitle);

        card.addView(body);
        sectionsContainer.addView(card);
        return body;
    }

    private void addField(LinearLayout container, int labelRes, String value) {
        addClickableField(container, getContext().getString(labelRes), value, null, false);
    }

    private void addClickableField(LinearLayout container, String label, String value,
                                   Runnable onClick, boolean clickable) {
        View row = LayoutInflater.from(getContext()).inflate(R.layout.item_wiki_field, container, false);
        TextView tvLabel = row.findViewById(R.id.tv_field_label);
        TextView tvValue = row.findViewById(R.id.tv_field_value);
        tvLabel.setText(label);
        String shown = TextUtils.isEmpty(value) ? "—" : value;
        tvValue.setText(shown);
        if (clickable && onClick != null) {
            tvValue.setTextColor(com.google.android.material.color.MaterialColors.getColor(tvValue,
                    androidx.appcompat.R.attr.colorPrimary));
            row.setOnClickListener(v -> {
                HapticsManager.tap(v);
                onClick.run();
            });
        }
        container.addView(row);
    }

    /**
     * 贡献记录行：改用统一列表行，不再在区块卡片里再套一层卡片。
     *
     * <p>原先每条记录是一个内嵌 {@link MaterialCardView}，与外层区块卡形成「卡中卡」，
     * 层级噪声大且每条记录多占 16dp 内边距。现在改为「标题 + 说明 + 分隔线」，
     * 与设置页、贡献页行节奏一致。
     */
    private void addRecordRow(LinearLayout container, SongWiki.Record r) {
        Context ctx = getContext();
        // 第一条记录前不加分隔线（child 0 是区块标题）
        if (container.getChildCount() > 1) {
            MaterialDivider divider = new MaterialDivider(ctx);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            container.addView(divider);
        }

        Md3ListItemView row = new Md3ListItemView(ctx);
        row.setTitle(TextUtils.isEmpty(r.nickName)
                ? ctx.getString(R.string.unknown_creator) : r.nickName);
        String approve = r.approveNames.isEmpty()
                ? ctx.getString(R.string.other_approve)
                : String.join("、", r.approveNames);
        row.setSupporting(approve + " · " + formatRecordTime(r.createTime));
        if (r.userId > 0) {
            row.setTrailingMode(Md3ListItemView.TRAILING_CHEVRON);
            row.setOnClickListener(v -> {
                HapticsManager.tap(v);
                WikiLinks.open(ctx, WikiLinks.userUrl(r.userId));
            });
        }
        container.addView(row);
    }

    private String formatRecordTime(long millis) {
        if (millis <= 0L) {
            return getContext().getString(R.string.unknown_time);
        }
        long ms = millis < 100000000000L ? millis * 1000L : millis;
        try {
            return RECORD_TIME.format(new Date(ms));
        } catch (Exception e) {
            return getContext().getString(R.string.unknown_time);
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 状态切换
    // ------------------------------------------------------------------

    private static final int STATE_CONTENT = 1;
    private static final int STATE_LOADING = 2;
    private static final int STATE_EMPTY = 3;
    private static final int STATE_ERROR = 4;

    private int currentStateTag = STATE_EMPTY;

    private void switchState(int state) {
        currentStateTag = state;
        contentScroll.setVisibility(state == STATE_CONTENT ? VISIBLE : GONE);
        stateLoading.setVisibility(state == STATE_LOADING ? VISIBLE : GONE);
        stateEmpty.setVisibility(state == STATE_EMPTY ? VISIBLE : GONE);
        stateError.setVisibility(state == STATE_ERROR ? VISIBLE : GONE);
        setInputEnabled(state != STATE_LOADING);
    }

    private void setInputEnabled(boolean enabled) {
        btnQuery.setEnabled(enabled);
        btnPaste.setEnabled(enabled);
        etQuery.setEnabled(enabled);
        if (tilQuery != null) {
            tilQuery.setEnabled(enabled);
        }
    }

    private void showLoading(String text) {
        tvLoadingText.setText(text);
        switchState(STATE_LOADING);
    }

    private void showEmptyState() {
        renderHistory();
        switchState(STATE_EMPTY);
    }

    private void showError(String message) {
        tvErrorMessage.setText(message);
        switchState(STATE_ERROR);
    }

    private int currentScrollY() {
        if (contentScroll instanceof androidx.core.widget.NestedScrollView) {
            return ((androidx.core.widget.NestedScrollView) contentScroll).getScrollY();
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // 历史 / 剪贴板 / 复制
    // ------------------------------------------------------------------

    /**
     * 查询历史：横向可滑动的 Chip 行。
     *
     * <p>原先最多 5 条通栏描边按钮，纵向占用近 200dp，把「空态」压得很低；
     * Chip 更紧凑且能横向容纳更多条，符合 MD3 的 assist chip 用法。
     */
    private void renderHistory() {
        historyContainer.removeAllViews();
        List<QueryHistory.Entry> list = historyStore.load();
        if (list.isEmpty()) {
            tvHistoryTitle.setVisibility(GONE);
            historyScroll.setVisibility(GONE);
            return;
        }
        tvHistoryTitle.setVisibility(VISIBLE);
        historyScroll.setVisibility(VISIBLE);
        int limit = Math.min(list.size(), 8);
        for (int i = 0; i < limit; i++) {
            final QueryHistory.Entry e = list.get(i);
            Chip chip = new Chip(getContext());
            chip.setText(e.displayTitle());
            chip.setCheckable(false);
            chip.setEnsureMinTouchTargetSize(true);
            chip.setOnClickListener(v -> {
                HapticsManager.tap(v);
                submit(e.displayTitle());
            });
            historyContainer.addView(chip);
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                Feedback.show(this, R.string.wiki_clip_empty);
                return;
            }
            CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
            if (TextUtils.isEmpty(text)) {
                Feedback.show(this, R.string.wiki_clip_empty);
                return;
            }
            etQuery.setText(text.toString().trim());
            etQuery.setSelection(etQuery.getText() == null ? 0 : etQuery.getText().length());
            Feedback.show(this, R.string.wiki_clip_pasted);
        } catch (Exception e) {
            Feedback.show(this, R.string.wiki_clip_empty);
        }
    }

    private void copyJson() {
        if (current == null || TextUtils.isEmpty(current.rawJson)) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(
                    getContext().getString(R.string.wiki_clip_label), current.rawJson));
            Feedback.show(this, R.string.wiki_clip_copied);
        } catch (Exception e) {
            Feedback.show(this, R.string.wiki_failed);
        }
    }

    private void openSongPage() {
        if (currentSongId > 0) {
            WikiLinks.open(getContext(), WikiLinks.songUrl(currentSongId));
        }
    }
}
