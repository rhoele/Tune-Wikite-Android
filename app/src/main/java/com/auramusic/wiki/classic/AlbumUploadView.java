package com.auramusic.wiki.classic;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 专辑上传页（对应原 APK「补充缺失专辑」）。
 *
 * <p>能力：
 * <ul>
 *   <li>目标艺人搜索与专辑艺人维护，移除的艺人回到候选列表首位；</li>
 *   <li>封面选择 + 1:1 裁剪，提交原图与裁剪两张 NOS 文件；</li>
 *   <li>多选音频 / 选择文件夹批量导入，导入即后台上传；</li>
 *   <li>歌曲列表拖拽排序，按碟分组自动重排曲序；歌曲可编辑名称、艺人、语种、曲风、碟序；</li>
 *   <li>按原 APK 的 albumVo 结构提交到 /api/rep/ugc/add/artist/album。</li>
 * </ul>
 */
public class AlbumUploadView extends FrameLayout {

    private static final String[] AUDIO_EXTS = {
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ape", ".ogg", ".opus"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * 上传线程池：封面与多首音频并行上传。
     *
     * <p>3 个线程是经验值：再多容易被 NOS 限速，反而拉长整体耗时；
     * 进度聚合与异常回调仍统一 post 回主线程，保证 UI 状态只在主线程修改。
     */
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(4);
    /** 读取音频标签的独立线程池：与上传解耦，保证后加入的曲目也能立刻改名 */
    private final ExecutorService metaExecutor = Executors.newFixedThreadPool(3);
    /** 艺人搜索独立线程：避免长音频上传阻塞输入联想 */
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private NestedScrollView scroll;
    private TextInputEditText targetInput;
    private ChipGroup targetChips;
    private LinearProgressIndicator artistSearchProgress;
    private RecyclerView artistResults;
    private TextView artistEmpty;
    private ArtistResultAdapter artistAdapter;
    private ImageView coverView;
    private TextView coverState;
    private MaterialButton coverButton;
    private TextInputEditText albumName;
    private TextInputEditText albumSubtitle;
    private TextInputEditText albumTrans;
    private MaterialAutoCompleteTextView albumType;
    private ChipGroup albumArtistChips;
    private TextView artistSuggestLabel;
    private ChipGroup artistSuggestChips;
    private TextInputEditText company;
    private ChipGroup companyRecoChips;
    private TextView companyHistoryLabel;
    private ChipGroup companyHistoryChips;
    private TextView publishValue;
    private ChipGroup languageChips;
    private ChipGroup tagChips;
    private TextInputEditText source;
    /** 专辑介绍：提交时必须写进 albumVo.production（notes 是「补充备注」，审核端才可见） */
    private TextInputEditText intro;
    private MaterialCheckBox agreement;
    private RecyclerView songList;
    private TextView songsEmpty;
    private TextView statusView;
    private LinearProgressIndicator progress;
    private MaterialButton submitButton;

    private MaterialCardView bannerCard;
    private ImageView bannerIcon;
    private TextView bannerTitle;
    private TextView bannerDesc;

    /** 顶栏上传进度回调（封面 + 歌曲聚合后的平均进度） */
    public interface UploadListener {
        void onUploadProgress(int percent, boolean active);
    }

    private UploadListener uploadListener;
    private final Map<Object, Integer> uploadPercents = new LinkedHashMap<>();

    private AlbumSongAdapter adapter;
    private final List<AlbumDraft.Song> songs = new ArrayList<>();
    /** 每首歌当前在途的上传任务；替换音频 / 移除曲目时据此撤掉聚合进度并打断旧任务 */
    private final Map<AlbumDraft.Song, UploadTask> activeUploads =
            new java.util.IdentityHashMap<>();

    /** 一次上传任务：{@code cancelled} 置位后分块循环会在一个分块内退出。 */
    private static final class UploadTask {
        final Object key = new Object();
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
    }

    /** 导入音频的复制线程：大文件同步复制会卡住主线程，看起来像「点了没反应」 */
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private final List<AlbumDraft.Artist> albumArtists = new ArrayList<>();
    private final List<AlbumDraft.Artist> removedAlbumArtists = new ArrayList<>();
    /** 目标艺人待选：提交成功或手动取消后可一键填回，且必须保留有效 artistId。 */
    private final List<AlbumDraft.Artist> targetSuggestions = new ArrayList<>();
    private final Set<String> selectedLanguages = new LinkedHashSet<>();
    private final Set<AlbumDraft.Tag> selectedTags = new LinkedHashSet<>();
    /**
     * 曲风字典：必须用接口返回的 id。
     *
     * <p>自带一份写死的 id 会让服务端错配（例如「电子」被落成「中国传统特色-民乐」），
     * 且审核前不可见，所以这里只接受 {@link NetEaseWikiClient#fetchStyleTags} 的结果。
     */
    private final List<AlbumDraft.Tag> styleTags = new ArrayList<>();
    private boolean styleTagsLoading;
    private boolean languagesExpanded;
    private boolean tagsExpanded;

    private AlbumDraft.Artist targetArtist;
    private Long publishTime;
    private String selectedAlbumType = AlbumDraft.ALBUM_TYPES[0];
    /** 一级搜索防抖与乱序保护 */
    private final Runnable searchRunnable = this::runArtistSearch;
    private int artistSearchSeq;
    /** 最近一次通过分享链接识别到的艺人 ID，避免同一链接重复请求 */
    private long resolvedArtistLinkId = -1L;
    private boolean suppressArtistSearch;
    private File coverRawFile;
    private File coverSquareFile;
    private NosUploader.NosFile coverRawNos;
    private NosUploader.NosFile coverSquareNos;
    private boolean coverUploading;
    /** 封面上传代次：换封面后旧任务的结果与进度回调一律作废 */
    private int coverToken;
    /** 封面最近一次上传失败的原因；为空表示没有失败。 */
    private String coverError;
    private boolean submitting;

    /**
     * 最近一次校验失败需要定位的字段 id（0 表示无具体字段）。
     *
     * <p>校验仍返回同一条文案，只额外记录「错在哪个控件」，供提交时滚动定位与行内报错。
     */
    @IdRes
    private int invalidFieldId;

    /** 离开时被临时取下焦点的输入框：回来再静默恢复焦点（只恢复焦点，不拉起输入法）。 */
    @Nullable
    private View focusOnReturn;
    /** 离开时的页面滚动位置：直接还焦点会把它滚进可视区，导致整页被强制跳走。 */
    private int scrollYOnFocusLoss;

    /** 需要显示行内错误的文本字段（其外层 TextInputLayout）。 */
    private static final int[] FIELD_LAYOUT_IDS = {
            R.id.til_au_target, R.id.til_au_name, R.id.til_au_company,
            R.id.til_au_source, R.id.til_au_type
    };

    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickGallery;
    private ActivityResultLauncher<String[]> pickImageDocument;
    private ActivityResultLauncher<Intent> cropImage;
    private ActivityResultLauncher<String[]> pickAudio;
    private ActivityResultLauncher<Uri> pickFolder;
    private ActivityResultLauncher<String[]> replaceAudioLauncher;
    private AlbumDraft.Song replacingSong;

    public AlbumUploadView(Context context) {
        super(context);
        init(context);
    }

    public AlbumUploadView(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AlbumUploadView(Context context, @Nullable android.util.AttributeSet attrs,
                           int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_album_upload, this, true);
        scroll = findViewById(R.id.au_scroll);
        targetInput = findViewById(R.id.et_au_target);
        targetChips = findViewById(R.id.chip_au_target);
        artistSearchProgress = findViewById(R.id.pb_au_artist_search);
        artistResults = findViewById(R.id.rv_au_artist_results);
        artistEmpty = findViewById(R.id.tv_au_artist_empty);
        coverView = findViewById(R.id.iv_au_cover);
        coverState = findViewById(R.id.tv_au_cover_state);
        coverButton = findViewById(R.id.btn_au_cover);
        albumName = findViewById(R.id.et_au_name);
        albumSubtitle = findViewById(R.id.et_au_subtitle);
        albumTrans = findViewById(R.id.et_au_trans);
        albumType = findViewById(R.id.act_au_type);
        albumArtistChips = findViewById(R.id.chip_au_album_artists);
        artistSuggestLabel = findViewById(R.id.tv_au_artist_suggest_label);
        artistSuggestChips = findViewById(R.id.chip_au_artist_suggestions);
        company = findViewById(R.id.et_au_company);
        companyRecoChips = findViewById(R.id.chip_au_company_reco);
        companyHistoryLabel = findViewById(R.id.tv_au_company_history_label);
        companyHistoryChips = findViewById(R.id.chip_au_company_history);
        publishValue = findViewById(R.id.tv_au_publish_value);
        languageChips = findViewById(R.id.chip_au_language);
        tagChips = findViewById(R.id.chip_au_tags);
        source = findViewById(R.id.et_au_source);
        intro = findViewById(R.id.et_au_notes);
        agreement = findViewById(R.id.cb_au_agree);
        songList = findViewById(R.id.rv_au_songs);
        songsEmpty = findViewById(R.id.tv_au_songs_empty);
        statusView = findViewById(R.id.tv_au_status);
        progress = findViewById(R.id.pb_au);
        submitButton = findViewById(R.id.btn_au_submit);
        bannerCard = findViewById(R.id.card_au_banner);
        bannerIcon = findViewById(R.id.iv_au_banner_icon);
        bannerTitle = findViewById(R.id.tv_au_banner_title);
        bannerDesc = findViewById(R.id.tv_au_banner_desc);

        bindFieldErrorClear();
        // 多行字段内容超出框体时，允许在框内上下滑动查看
        InputScroll.enable(albumName, albumSubtitle, albumTrans, company, source, intro);
        installFocusClearing();

        artistAdapter = new ArtistResultAdapter(this::onTargetArtistPicked);
        artistResults.setLayoutManager(new LinearLayoutManager(context));
        artistResults.setAdapter(artistAdapter);
        findViewById(R.id.btn_au_banner_close).setOnClickListener(v ->
                MotionManager.animateVisibility(getContext(), bannerCard, false));

        // 候选固定为全量：避免状态恢复时被 AutoCompleteTextView 按当前值过滤
        albumType.setAdapter(new FixedItemsAdapter(context, AlbumDraft.ALBUM_TYPES));
        albumType.setThreshold(0);
        albumType.setText(selectedAlbumType, false);
        albumType.setOnItemClickListener((parent, view, position, id) ->
                selectedAlbumType = AlbumDraft.ALBUM_TYPES[position]);

        buildLanguageChips();
        buildTagChips();
        loadStyleTags(null);

        adapter = new AlbumSongAdapter();
        adapter.attach(songs);
        adapter.setListener(new AlbumSongAdapter.Listener() {
            @Override
            public void onSongClick(AlbumDraft.Song song) {
                showSongEditor(song);
            }

            @Override
            public void onStartDrag(RecyclerView.ViewHolder holder) {
                touchHelper.startDrag(holder);
            }
        });
        songList.setLayoutManager(new LinearLayoutManager(context));
        songList.setAdapter(adapter);
        // 与 M3 控件级过渡保持同一时长，插入 / 删除 / 拖拽排序不再瞬变
        RecyclerView.ItemAnimator animator = songList.getItemAnimator();
        if (animator != null) {
            long duration = MotionManager.controlDuration(context);
            animator.setAddDuration(duration);
            animator.setRemoveDuration(duration);
            animator.setMoveDuration(duration);
            animator.setChangeDuration(duration);
            if (animator instanceof androidx.recyclerview.widget.SimpleItemAnimator) {
                // 拖拽时只更新曲序文案，禁用交叉淡入避免整行闪烁
                ((androidx.recyclerview.widget.SimpleItemAnimator) animator)
                        .setSupportsChangeAnimations(false);
            }
        }
        touchHelper.attachToRecyclerView(songList);

        // 一级实时搜索：输入即触发（320ms 防抖），结果在卡片内直接预览
        targetInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressArtistSearch) {
                    return;
                }
                String keyword = s == null ? "" : s.toString().trim();
                // 粘贴「分享歌手」口令时直接按链接里的 ID 取艺人，不再走关键词搜索
                long linkId = WikiIdParser.parseArtistId(keyword);
                if (linkId > 0) {
                    main.removeCallbacks(searchRunnable);
                    if (linkId != resolvedArtistLinkId || targetArtist == null) {
                        resolveArtistLink(linkId);
                    }
                    return;
                }
                resolvedArtistLinkId = -1L;
                main.removeCallbacks(searchRunnable);
                if (keyword.isEmpty()) {
                    artistSearchSeq++;
                    showArtistResults(new ArrayList<>(), false);
                    return;
                }
                main.postDelayed(searchRunnable, 320L);
            }
        });
        targetInput.setOnEditorActionListener((v, actionId, event) -> {
            main.removeCallbacks(searchRunnable);
            runArtistSearch();
            return true;
        });
        company.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshCompanyHistory();
            }
        });
        findViewById(R.id.btn_au_add_artist).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickAlbumArtist();
        });
        coverButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            chooseCoverSource();
        });
        findViewById(R.id.row_au_publish).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickPublishDate();
        });
        findViewById(R.id.btn_au_add_audio).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickAudio.launch(new String[]{"audio/*"});
        });
        findViewById(R.id.btn_au_pick_folder).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pickFolder.launch(null);
        });
        submitButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            submit();
        });

        ComponentActivity activity = (ComponentActivity) context;
        pickGallery = activity.registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (uri != null) {
                        beginCrop(uri);
                    }
                });
        pickImageDocument = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        beginCrop(uri);
                    }
                });
        cropImage = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        String path = result.getData().getStringExtra(CoverCropActivity.EXTRA_RESULT);
                        if (path != null) {
                            onCoverCropped(new File(path));
                        }
                    }
                });
        pickAudio = activity.registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        addAudioUris(uris);
                    }
                });
        pickFolder = activity.registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        scanFolder(uri);
                    }
                });
        replaceAudioLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris != null && !uris.isEmpty() && replacingSong != null) {
                        // 与「添加音频」同一个系统选择器：多选时只认第一首
                        replaceAudio(replacingSong, uris.get(0));
                    }
                });

        renderArtistSuggestions();
        renderCompanyRecommendation();
        refreshCompanyHistory();
    }

    private final ItemTouchHelper touchHelper = new ItemTouchHelper(
            new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                                      RecyclerView.ViewHolder target) {
                    int from = viewHolder.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    if (from < 0 || to < 0) {
                        return false;
                    }
                    adapter.move(from, to);
                    // 每跨过一行给一次轻触感，和视觉位移对齐
                    HapticsManager.tap(viewHolder.itemView);
                    renumber();
                    return true;
                }

                @Override
                public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                    // 只支持拖拽排序
                }

                @Override
                public boolean isLongPressDragEnabled() {
                    return false;
                }

                @Override
                public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder,
                                              int actionState) {
                    super.onSelectedChanged(viewHolder, actionState);
                    if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                        // Expressive 拖拽：拿起时抬升 + 轻微放大，并有独立触感
                        HapticsManager.longPress(viewHolder.itemView);
                        lift(viewHolder.itemView, true);
                    } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                        // 拖拽因任何原因结束（含被动画/滚动打断）时兜底复位所有行
                        resetAllLifts(songList);
                    }
                }

                @Override
                public void clearView(RecyclerView recyclerView,
                                      RecyclerView.ViewHolder viewHolder) {
                    super.clearView(recyclerView, viewHolder);
                    lift(viewHolder.itemView, false);
                    // RecyclerView 换位时可能把抬升状态留在别的行上，统一复位
                    resetAllLifts(recyclerView);
                    HapticsManager.tap(viewHolder.itemView);
                    renumber();
                    // 换位后的行不会自动重绑：整行刷一次，保证曲序 / 上传进度 / 对勾
                    // 与当前歌曲一致，不残留拖拽前的状态
                    adapter.refreshAfterDrag();
                }

                @Override
                public long getAnimationDuration(RecyclerView recyclerView,
                                                 int animationType, float animateDx,
                                                 float animateDy) {
                    // 回落动画与 Material 控件级过渡统一节奏
                    return MotionManager.controlDuration(recyclerView.getContext());
                }

                @Override
                public float getMoveThreshold(RecyclerView.ViewHolder viewHolder) {
                    // 越过 32% 行高即换位，拖动反馈更跟手
                    return 0.32f;
                }
            });

    /** 拖拽拿起 / 放下的视觉状态：抬升、放大随动效强度变化。 */
    private void lift(View itemView, boolean lifted) {
        if (itemView == null) {
            return;
        }
        if (!lifted) {
            // 直接复位而不是播放回落动画：松手时视图可能已被 RecyclerView 换位 / 回收，
            // 动画还没跑完就被取消，会让个别行残留「放大 + 阴影」
            resetLift(itemView);
            return;
        }
        itemView.animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .translationZ(dp(10))
                .setDuration(MotionManager.controlDuration(getContext()))
                .setInterpolator(MotionManager.emphasized(getContext()))
                .start();
    }

    /** 把单行恢复到静止外观（无放大、无抬升阴影）。 */
    private void resetLift(View itemView) {
        if (itemView == null) {
            return;
        }
        itemView.animate().cancel();
        itemView.setScaleX(1f);
        itemView.setScaleY(1f);
        itemView.setTranslationZ(0f);
    }

    /** 复位列表中所有可见行；拖拽结束或被打断时调用，避免抬升状态残留在任意一行上。 */
    private void resetAllLifts(@Nullable RecyclerView list) {
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            resetLift(list.getChildAt(i));
        }
    }

    /** 页面主滚动容器（供底栏模糊跟随超限位移）。 */
    androidx.core.widget.NestedScrollView mainScroll() {
        return scroll;
    }

    /** 底部覆盖层高度（px）：只增加滚动余量，避免与悬浮底栏争夺底部空间。 */
    public void setBottomInset(int px) {
        if (scroll == null) {
            return;
        }
        if (scroll.getPaddingBottom() != px) {
            scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                    scroll.getPaddingRight(), px);
        }
    }

    /** 宿主注册顶栏上传进度回调。 */
    public void setUploadListener(UploadListener listener) {
        this.uploadListener = listener;
        dispatchUploadProgress();
    }

    /** 内容滚动时通知宿主重新采样底栏玻璃，保证透明底栏在每个页面都跟随内容。 */
    public void setOnContentScrollListener(@Nullable Runnable listener) {
        if (scroll == null) {
            return;
        }
        scroll.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (v, x, y, oldX, oldY) -> {
                    if (y != oldY && listener != null) {
                        listener.run();
                    }
                });
    }

    private void beginUploadTask(Object key) {
        uploadPercents.put(key, 0);
        dispatchUploadProgress();
    }

    private void updateUploadProgress(Object key, int percent) {
        if (!uploadPercents.containsKey(key)) {
            return;
        }
        uploadPercents.put(key, Math.max(0, Math.min(100, percent)));
        dispatchUploadProgress();
    }

    private void endUploadTask(Object key) {
        uploadPercents.remove(key);
        dispatchUploadProgress();
    }

    private void postUploadProgress(Object key, int percent) {
        main.post(() -> updateUploadProgress(key, percent));
    }

    /** 聚合所有在途上传的平均进度；没有在途任务时通知宿主收起进度条。 */
    private void dispatchUploadProgress() {
        if (uploadListener == null) {
            return;
        }
        if (uploadPercents.isEmpty()) {
            uploadListener.onUploadProgress(0, false);
            return;
        }
        int sum = 0;
        for (Integer value : uploadPercents.values()) {
            sum += value == null ? 0 : value;
        }
        uploadListener.onUploadProgress(sum / uploadPercents.size(), true);
    }

    /** 由宿主在切换到本页时调用：接收声云下载页交接的音频、封面与资料来源。 */
    public void consumeTransferStash() {
        if (!TransferStash.hasContent()) {
            return;
        }
        List<TransferStash.Track> tracks = TransferStash.tracks();
        File cover = TransferStash.cover();
        String sourceUrl = TransferStash.sourceUrl();
        TransferStash.clear();

        // 列表顺序即曲序：set 里的曲目按解析顺序追加，renumber 再统一编号
        List<AlbumDraft.Song> added = new ArrayList<>();
        int startIndex = songs.size();
        for (TransferStash.Track item : tracks) {
            AlbumDraft.Song song = new AlbumDraft.Song();
            song.file = item.file;
            song.mime = guessMime(item.file.getName());
            song.name = TextUtils.isEmpty(item.title)
                    ? AlbumDraft.cleanSongName(stripExt(item.file.getName())) : item.title;
            // 声云解析出的标题已经是正式歌名，不再被音频标签覆盖
            song.nameEdited = !TextUtils.isEmpty(item.title);
            song.artists.addAll(albumArtists);
            song.language = selectedLanguages.isEmpty() ? "" : selectedLanguages.iterator().next();
            song.tags.addAll(selectedTags);
            songs.add(song);
            added.add(song);
        }
        if (!added.isEmpty()) {
            adapter.notifyItemRangeInserted(startIndex, added.size());
            renumber();
            updateSongsEmpty();
            // 插入完成后再启动上传，避免 refreshSong 命中尚未公布的位置
            for (AlbumDraft.Song song : added) {
                resolveSongName(song);
                uploadSong(song);
            }
        }
        if (cover != null && cover.isFile()) {
            applyCover(cover, cover);
        }
        // 声云链接可选传入「资料来源」
        if (!TextUtils.isEmpty(sourceUrl)) {
            source.setText(sourceUrl);
            source.setSelection(source.length());
        }
        if (tracks.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (cover != null) {
                parts.add(getContext().getString(R.string.sc_handoff_cover));
            }
            if (!TextUtils.isEmpty(sourceUrl)) {
                parts.add(getContext().getString(R.string.sc_source_label));
            }
            statusView.setText(getContext().getString(R.string.au_handoff_loaded,
                    TextUtils.join(" / ", parts)));
        } else {
            String label = TextUtils.isEmpty(tracks.get(0).title) ? "" : tracks.get(0).title;
            statusView.setText(getContext().getString(R.string.au_handoff_loaded, label)
                    + (tracks.size() > 1
                    ? getContext().getString(R.string.au_handoff_batch, tracks.size()) : ""));
        }
    }

    // ------------------------------------------------------------------
    // 基础控件构建
    // ------------------------------------------------------------------

    private void buildLanguageChips() {
        ChipOptions.bindLanguages(languageChips, selectedLanguages, languagesExpanded, () -> {
            languagesExpanded = !languagesExpanded;
            buildLanguageChips();
        });
    }

    private void buildTagChips() {
        bindStyleTags(tagChips, selectedTags, tagsExpanded, () -> {
            tagsExpanded = !tagsExpanded;
            buildTagChips();
        });
    }

    /** 曲风 chip：字典未就绪时显示加载 / 失败重试，避免把空字典当成「没有曲风」。 */
    private void bindStyleTags(ChipGroup group, Set<AlbumDraft.Tag> selected, boolean expanded,
                               Runnable onToggleExpand) {
        if (styleTags.isEmpty()) {
            if (styleTagsLoading) {
                ChipOptions.showHint(group, R.string.au_tags_loading, null);
            } else {
                ChipOptions.showHint(group, R.string.au_tags_load_failed,
                        v -> loadStyleTags(() ->
                                bindStyleTags(group, selected, expanded, onToggleExpand)));
            }
            return;
        }
        ChipOptions.bindTags(group, styleTags, selected, 3, expanded, onToggleExpand);
    }

    /** 拉取曲风字典；成功后重建曲风条，并执行调用方追加的刷新。 */
    private void loadStyleTags(@Nullable Runnable afterLoad) {
        if (styleTagsLoading || !styleTags.isEmpty()) {
            if (afterLoad != null) {
                afterLoad.run();
            }
            return;
        }
        styleTagsLoading = true;
        buildTagChips();
        final Context appContext = getContext().getApplicationContext();
        searchExecutor.execute(() -> {
            List<AlbumDraft.Tag> loaded = null;
            try {
                loaded = NetEaseWikiClient.fetchStyleTags(appContext, null);
            } catch (Exception ignored) {
                // 失败时保留重试入口
            }
            final List<AlbumDraft.Tag> data = loaded;
            main.post(() -> {
                styleTagsLoading = false;
                if (data != null && !data.isEmpty()) {
                    styleTags.addAll(data);
                }
                buildTagChips();
                if (afterLoad != null) {
                    afterLoad.run();
                }
            });
        });
    }

    private Chip newChip(String text, boolean checkable) {
        Chip chip = new Chip(getContext());
        chip.setText(text);
        chip.setCheckable(checkable);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setMinHeight(dp(40));
        return chip;
    }

    private void renderArtistChips(ChipGroup group, List<AlbumDraft.Artist> artists,
                                   ArtistRemove onRemove) {
        MotionManager.beginAutoTransition(getContext(), group);
        group.removeAllViews();
        for (final AlbumDraft.Artist artist : artists) {
            Chip chip = new Chip(getContext());
            chip.setText(artist.name);
            chip.setCloseIconVisible(true);
            chip.setEnsureMinTouchTargetSize(true);
            chip.setMinHeight(dp(40));
            chip.setOnCloseIconClickListener(v -> {
                artists.remove(artist);
                renderArtistChips(group, artists, onRemove);
                if (onRemove != null) {
                    onRemove.onRemoved(artist);
                }
            });
            group.addView(chip);
        }
    }

    private interface ArtistRemove {
        void onRemoved(AlbumDraft.Artist artist);
    }

    // ------------------------------------------------------------------
    // 目标艺人 / 专辑艺人
    // ------------------------------------------------------------------

    /**
     * 一级实时搜索：输入框内容变化后 320ms 触发，结果直接渲染在卡片内。
     * 通过递增 seq 丢弃过期响应，避免快速输入时旧结果覆盖新结果。
     */
    private void runArtistSearch() {
        String keyword = text(targetInput).trim();
        if (TextUtils.isEmpty(keyword)) {
            showArtistResults(new ArrayList<>(), false);
            return;
        }
        final int seq = ++artistSearchSeq;
        MotionManager.animateVisibility(getContext(), artistSearchProgress, true);
        artistEmpty.setVisibility(View.GONE);
        searchExecutor.execute(() -> {
            List<AlbumDraft.Artist> results = null;
            String error = null;
            try {
                results = NetEaseWikiClient.searchArtists(
                        getContext().getApplicationContext(), keyword, null);
            } catch (Exception e) {
                error = messageOf(e);
            }
            final List<AlbumDraft.Artist> data = results;
            final String message = error;
            main.post(() -> {
                if (seq != artistSearchSeq) {
                    return;
                }
                MotionManager.animateVisibility(getContext(), artistSearchProgress, false);
                if (message != null) {
                    showArtistResults(new ArrayList<>(), false);
                    toast(message);
                    return;
                }
                showArtistResults(data, true, keyword);
            });
        });
    }

    private void showArtistResults(List<AlbumDraft.Artist> items, boolean fromSearch) {
        showArtistResults(items, fromSearch, null);
    }

    /**
     * 点击页面空白区域时取消输入框焦点并收起输入法。
     *
     * <p>只处理落在「当前焦点输入框」之外的按下事件：点在输入框内继续编辑，
     * 点按钮 / chip 等控件则不拦截，避免影响它们自己的点击与波纹。
     */
    private void installFocusClearing() {
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                View focused = findFocus();
                if (focused instanceof EditText && !hitInside(focused, event)) {
                    clearFieldFocus();
                }
            }
            return false;
        });
    }

    private boolean hitInside(View view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x <= location[0] + view.getWidth()
                && y >= location[1] && y <= location[1] + view.getHeight();
    }

    /** 取消当前输入框焦点并收起输入法（打开弹窗前也复用此逻辑）。 */
    private void clearFieldFocus() {
        View focused = findFocus();
        if (focused != null) {
            focused.clearFocus();
        }
        hideKeyboard();
    }

    /** 分享链接 → 艺人 ID → 详情；成功后直接作为目标艺人填入。 */
    private void resolveArtistLink(final long artistId) {
        final int seq = ++artistSearchSeq;
        resolvedArtistLinkId = artistId;
        MotionManager.animateVisibility(getContext(), artistSearchProgress, true);
        artistEmpty.setVisibility(View.GONE);
        searchExecutor.execute(() -> {
            AlbumDraft.Artist found = null;
            String error = null;
            try {
                found = NetEaseWikiClient.fetchArtist(
                        getContext().getApplicationContext(), artistId, null);
            } catch (Exception e) {
                error = messageOf(e);
            }
            final AlbumDraft.Artist artist = found;
            final String message = error;
            main.post(() -> {
                if (seq != artistSearchSeq) {
                    return;
                }
                MotionManager.animateVisibility(getContext(), artistSearchProgress, false);
                if (artist != null) {
                    onTargetArtistPicked(artist);
                } else {
                    // 解析失败不阻断上传：保留用户粘贴的原始输入，允许继续手动填写
                    toast(message != null
                            ? message
                            : getContext().getString(R.string.au_artist_link_failed));
                }
            });
        });
    }

    /** 渲染目标艺人的一级搜索结果；这里只允许选择已有艺人，不能直接新建。 */
    private void showArtistResults(List<AlbumDraft.Artist> items, boolean fromSearch,
                                   String keyword) {
        boolean has = items != null && !items.isEmpty();
        artistAdapter.submit(items);
        MotionManager.animateVisibility(getContext(), artistResults, has);
        artistEmpty.setVisibility(fromSearch && !has ? View.VISIBLE : View.GONE);
    }

    private void onTargetArtistPicked(AlbumDraft.Artist artist) {
        if (artist == null) {
            return;
        }
        removeTargetSuggestion(artist);
        targetArtist = artist;
        // 目标艺人选中后自动同步为专辑艺人（与网页版 / 原 APK 一致）
        addAlbumArtist(artist);
        renderTargetChip();
        suppressArtistSearch = true;
        targetInput.setText(artist.name);
        targetInput.setSelection(targetInput.length());
        suppressArtistSearch = false;
        artistSearchSeq++;
        showArtistResults(new ArrayList<>(), false);
        hideKeyboard();
        statusView.setText("目标艺人：" + artist.name);
    }

    private void renderTargetChip() {
        MotionManager.beginAutoTransition(getContext(), targetChips);
        targetChips.removeAllViews();
        renderCompanyRecommendation();
        if (targetArtist == null) {
            for (final AlbumDraft.Artist artist : targetSuggestions) {
                Chip chip = newOutlinedChip(artist.name);
                chip.setChipIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_account));
                chip.setChipIconVisible(true);
                chip.setOnClickListener(v -> {
                    HapticsManager.tap(v);
                    onTargetArtistPicked(artist);
                });
                targetChips.addView(chip);
            }
            return;
        }
        Chip chip = new Chip(getContext());
        chip.setText(targetArtist.name);
        chip.setCloseIconVisible(true);
        chip.setEnsureMinTouchTargetSize(true);
        chip.setOnCloseIconClickListener(v -> {
            keepTargetSuggestion(targetArtist);
            targetArtist = null;
            renderTargetChip();
        });
        targetChips.addView(chip);
    }

    private void pickAlbumArtist() {
        ArtistPickerDialog.show(getContext(), new ArrayList<>(removedAlbumArtists), this::addAlbumArtist);
    }

    /** 封面来源：系统图库（Photo Picker）或本地存储（文档选择器）。 */
    private void chooseCoverSource() {
        ExpressiveMenu.show(coverButton, new CharSequence[]{
                getContext().getString(R.string.au_cover_source_gallery),
                getContext().getString(R.string.au_cover_source_storage),
        }, -1, which -> {
            if (which == 0) {
                pickGallery.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            } else {
                pickImageDocument.launch(new String[]{"image/*"});
            }
        });
    }

    /** 添加专辑艺人（按艺人身份去重），同时从待选列表移除。 */
    private void addAlbumArtist(AlbumDraft.Artist artist) {
        if (artist == null) {
            return;
        }
        if (!albumArtists.contains(artist)) {
            albumArtists.add(artist);
        }
        for (int i = removedAlbumArtists.size() - 1; i >= 0; i--) {
            if (removedAlbumArtists.get(i).equals(artist)) {
                removedAlbumArtists.remove(i);
            }
        }
        renderAlbumArtistChips();
    }

    /** 把艺人放入「可重新添加」待选列表（去重，最新的排在最前）。 */
    private void keepAddable(AlbumDraft.Artist artist) {
        if (artist == null || TextUtils.isEmpty(artist.name)) {
            return;
        }
        if (removedAlbumArtists.contains(artist)) {
            return;
        }
        removedAlbumArtists.add(0, artist);
    }

    /** 目标艺人待选只接受已有艺人（id > 0），提交后显示在目标艺人输入框下方。 */
    private void keepTargetSuggestion(AlbumDraft.Artist artist) {
        if (artist == null || artist.id <= 0 || TextUtils.isEmpty(artist.name)) {
            return;
        }
        removeTargetSuggestion(artist);
        targetSuggestions.add(0, artist);
    }

    private void removeTargetSuggestion(AlbumDraft.Artist artist) {
        if (artist == null) {
            return;
        }
        for (int i = targetSuggestions.size() - 1; i >= 0; i--) {
            if (targetSuggestions.get(i).equals(artist)) {
                targetSuggestions.remove(i);
            }
        }
    }

    private void renderAlbumArtistChips() {
        renderArtistChips(albumArtistChips, albumArtists, artist -> {
            // 被移除的艺人回到候选首位，下次可一键填回
            keepAddable(artist);
            renderArtistSuggestions();
        });
        renderArtistSuggestions();
    }

    /** 下方「可重新添加」区域：以可点击的描边 chip 展示候选艺人。 */
    private void renderArtistSuggestions() {
        if (artistSuggestChips == null) {
            return;
        }
        MotionManager.beginAutoTransition(getContext(), artistSuggestChips);
        artistSuggestChips.removeAllViews();
        boolean has = !removedAlbumArtists.isEmpty();
        artistSuggestLabel.setVisibility(has ? View.VISIBLE : View.GONE);
        artistSuggestChips.setVisibility(has ? View.VISIBLE : View.GONE);
        for (final AlbumDraft.Artist artist : removedAlbumArtists) {
            Chip chip = newOutlinedChip(artist.name);
            chip.setChipIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_add));
            chip.setChipIconVisible(true);
            chip.setOnClickListener(v -> {
                HapticsManager.tap(v);
                addAlbumArtist(artist);
            });
            artistSuggestChips.addView(chip);
        }
    }

    private Chip newOutlinedChip(String text) {
        Chip chip = newChip(text, false);
        chip.setCheckable(false);
        chip.setChipBackgroundColor(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        chip.setChipStrokeWidth(dp(1));
        chip.setChipStrokeColor(ColorStateList.valueOf(
                MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOutline)));
        return chip;
    }

    // ------------------------------------------------------------------
    // 发行公司：目标艺人推荐 + 历史输入联想
    // ------------------------------------------------------------------

    /** 字段旁的推荐项：目标艺人可一键填入发行公司。 */
    private void renderCompanyRecommendation() {
        if (companyRecoChips == null) {
            return;
        }
        MotionManager.beginAutoTransition(getContext(), companyRecoChips);
        companyRecoChips.removeAllViews();
        if (targetArtist == null || TextUtils.isEmpty(targetArtist.name)) {
            companyRecoChips.setVisibility(View.GONE);
            return;
        }
        Chip chip = newOutlinedChip(
                getContext().getString(R.string.au_company_reco, targetArtist.name));
        chip.setChipIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_account));
        chip.setChipIconVisible(true);
        chip.setOnClickListener(v -> {
            HapticsManager.tap(v);
            company.setText(targetArtist.name);
            company.setSelection(company.length());
            refreshCompanyHistory();
        });
        companyRecoChips.addView(chip);
        companyRecoChips.setVisibility(View.VISIBLE);
    }

    /** 仿浏览器输入联想：输入时实时匹配历史记录，最多展示 5 条。 */
    private void refreshCompanyHistory() {
        if (companyHistoryChips == null) {
            return;
        }
        String keyword = text(company).trim();
        MotionManager.beginAutoTransition(getContext(), companyHistoryChips);
        companyHistoryChips.removeAllViews();
        if (keyword.isEmpty()) {
            companyHistoryLabel.setVisibility(View.GONE);
            companyHistoryChips.setVisibility(View.GONE);
            return;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : WikiPrefs.companyHistory(getContext())) {
            if (value.equalsIgnoreCase(keyword)) {
                continue;
            }
            if (value.toLowerCase(Locale.ROOT).contains(lower)) {
                matches.add(value);
            }
            if (matches.size() >= 5) {
                break;
            }
        }
        if (matches.isEmpty()) {
            companyHistoryLabel.setVisibility(View.GONE);
            companyHistoryChips.setVisibility(View.GONE);
            return;
        }
        for (final String value : matches) {
            Chip chip = newOutlinedChip(value);
            chip.setOnClickListener(v -> {
                HapticsManager.tap(v);
                company.setText(value);
                company.setSelection(company.length());
                refreshCompanyHistory();
            });
            companyHistoryChips.addView(chip);
        }
        companyHistoryLabel.setVisibility(View.VISIBLE);
        companyHistoryChips.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // 封面
    // ------------------------------------------------------------------

    private void beginCrop(Uri uri) {
        try {
            coverRawFile = copyToCache(uri, "upload-cover", guessImageExt(uri));
        } catch (Exception e) {
            toast(getContext().getString(R.string.au_cover_pick_failed));
            return;
        }
        Intent intent = new Intent(getContext(), CoverCropActivity.class);
        intent.putExtra(CoverCropActivity.EXTRA_SOURCE, uri.toString());
        cropImage.launch(intent);
    }

    private void onCoverCropped(File square) {
        applyCover(coverRawFile, square);
    }

    private void applyCover(File raw, File square) {
        if (raw != null && (square == null || square.getAbsolutePath()
                .equals(raw.getAbsolutePath()))) {
            File generated = makeSquare(raw);
            if (generated != null) {
                square = generated;
            }
        }
        coverRawFile = raw;
        coverSquareFile = square;
        coverRawNos = null;
        coverSquareNos = null;
        coverError = null;
        coverUploading = true;
        coverState.setVisibility(View.VISIBLE);
        coverState.setText(R.string.au_cover_uploading);
        coverButton.setText(R.string.au_cover_change);
        Bitmap bmp = BitmapFactory.decodeFile(square.getAbsolutePath());
        if (bmp != null) {
            coverView.setPadding(0, 0, 0, 0);
            coverView.setImageBitmap(bmp);
            MotionManager.fadeInImage(getContext(), coverView);
        }
        MotionManager.animateVisibility(getContext(), coverState, true);
        uploadCover();
    }

    /** 把任意封面裁成 1:1（用于下载页传入的封面，保证 1v1 与母带原图都合规）。 */
    @Nullable
    private File makeSquare(File raw) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(raw.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            int edge = Math.min(bounds.outWidth, bounds.outHeight);
            while (edge / sample > 2048) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(raw.getAbsolutePath(), options);
            if (bitmap == null) {
                return null;
            }
            int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
            int left = (bitmap.getWidth() - size) / 2;
            int top = (bitmap.getHeight() - size) / 2;
            Bitmap square = Bitmap.createBitmap(bitmap, left, top, size, size);
            if (square != bitmap) {
                bitmap.recycle();
            }
            File out = new File(getContext().getCacheDir(),
                    "cover-square-" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                square.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            }
            square.recycle();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void uploadCover() {
        final File raw = coverRawFile;
        final File square = coverSquareFile;
        // 每次换封面都是一次新任务：用独立 key + 代次，避免旧任务结束进度条、
        // 或把上一张封面的 nos 结果写回当前状态
        final Object coverKey = new Object();
        final int token = ++coverToken;
        beginUploadTask(coverKey);
        uploadExecutor.execute(() -> {
            try {
                NosUploader.NosFile rawNos = raw == null ? null
                        : NosUploader.uploadImage(getContext(), raw, guessMime(raw.getName()),
                        percent -> postUploadProgress(coverKey, percent / 2));
                NosUploader.NosFile squareNos = square == null ? null
                        : NosUploader.uploadImage(getContext(), square, "image/jpeg",
                        percent -> postUploadProgress(coverKey, 50 + percent / 2));
                main.post(() -> {
                    endUploadTask(coverKey);
                    if (token != coverToken) {
                        return;
                    }
                    coverRawNos = rawNos;
                    coverSquareNos = squareNos;
                    coverUploading = false;
                    coverError = null;
                    coverState.setText(R.string.au_cover_uploaded);
                });
            } catch (Exception e) {
                main.post(() -> {
                    endUploadTask(coverKey);
                    if (token != coverToken) {
                        return;
                    }
                    coverUploading = false;
                    coverError = messageOf(e);
                    coverState.setText(getContext().getString(R.string.au_err_cover_failed,
                            messageOf(e)));
                });
            }
        });
    }

    private void pickPublishDate() {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setTitleText(R.string.au_date_pick);
        if (publishTime != null) {
            builder.setSelection(publishTime);
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            publishTime = selection;
            publishValue.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                    .format(new Date(selection)));
        });
        picker.show(((androidx.appcompat.app.AppCompatActivity) getContext())
                .getSupportFragmentManager(), "au-date");
    }

    // ------------------------------------------------------------------
    // 音频导入与上传
    // ------------------------------------------------------------------

    private void addAudioUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return;
        }
        // 复制放到后台：大文件同步复制会卡住主线程，用户会以为「点了没反应」再点一次
        final List<Uri> picked = new ArrayList<>(uris);
        statusView.setText(getContext().getString(R.string.au_importing, picked.size()));
        importExecutor.execute(() -> {
            final List<File> copied = new ArrayList<>();
            int failed = 0;
            for (Uri uri : picked) {
                File file = copyToCacheWithRetry(uri);
                if (file == null) {
                    failed++;
                } else {
                    copied.add(file);
                }
            }
            final int failedCount = failed;
            main.post(() -> {
                for (File file : copied) {
                    addAudioFile(file);
                }
                if (copied.isEmpty()) {
                    toast(getContext().getString(R.string.au_pick_audio_failed));
                } else if (failedCount > 0) {
                    toast(getContext().getString(R.string.au_import_partial, failedCount));
                }
                statusView.setText(R.string.au_submit_hint);
            });
        });
    }

    /**
     * 复制单个选中的音频，失败重试一次。
     *
     * <p>部分 provider 在 picker 返回后的一瞬间还读不到流，直接失败会让用户
     * 以为「添加没反应」，需要再操作一次。
     */
    @Nullable
    private File copyToCacheWithRetry(Uri uri) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return copyToCache(uri, "upload-audio", guessAudioExt(uri));
            } catch (Exception e) {
                if (attempt > 0) {
                    return null;
                }
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private void addAudioFile(File file) {
        AlbumDraft.Song song = buildSong(file);
        songs.add(song);
        // 插入动画由 RecyclerView 的 ItemAnimator 播放，而不是整表刷新
        adapter.notifyItemInserted(songs.size() - 1);
        renumber();
        updateSongsEmpty();
        resolveSongName(song);
        uploadSong(song);
    }

    private AlbumDraft.Song buildSong(File file) {
        AlbumDraft.Song song = new AlbumDraft.Song();
        song.file = file;
        song.mime = guessMime(file.getName());
        // 文件名只作为兜底：去掉「01 - 」这类系统前缀，元数据里的歌名在开始上传时补全
        song.name = AlbumDraft.cleanSongName(stripExt(file.getName()));
        song.artists.addAll(albumArtists);
        song.language = selectedLanguages.isEmpty() ? "" : selectedLanguages.iterator().next();
        // 与语种一致：专辑已选曲风时，后续添加的单曲默认沿用
        song.tags.addAll(selectedTags);
        return song;
    }

    private void showAudioFileName(TextView view, File file) {
        if (view == null) {
            return;
        }
        view.setText(file == null ? ""
                : getContext().getString(R.string.au_song_audio_file, file.getName()));
    }

    /** 读取音频标签里的标题；没有标签或格式不支持时返回空串。 */
    private String readAudioTitle(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            return title == null ? "" : title.trim();
        } catch (Exception e) {
            return "";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 释放失败不影响上传
            }
        }
    }

    private void scanFolder(Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (root == null) {
            toast(getContext().getString(R.string.au_folder_empty));
            return;
        }
        statusView.setText("正在读取文件夹…");
        executor.execute(() -> {
            List<File> found = new ArrayList<>();
            walk(root, found, 0);
            main.post(() -> {
                if (found.isEmpty()) {
                    statusView.setText(getContext().getString(R.string.au_folder_empty));
                    return;
                }
                int start = songs.size();
                List<AlbumDraft.Song> created = new ArrayList<>();
                for (File file : found) {
                    AlbumDraft.Song song = buildSong(file);
                    songs.add(song);
                    created.add(song);
                }
                adapter.notifyItemRangeInserted(start, created.size());
                renumber();
                updateSongsEmpty();
                for (AlbumDraft.Song song : created) {
                    resolveSongName(song);
                    uploadSong(song);
                }
                statusView.setText("已导入 " + found.size() + " 个音频");
            });
        });
    }

    private void walk(DocumentFile dir, List<File> out, int depth) {
        if (dir == null || depth > 8 || out.size() >= 300) {
            return;
        }
        DocumentFile[] children = dir.listFiles();
        for (DocumentFile child : children) {
            if (out.size() >= 300) {
                return;
            }
            if (child.isDirectory()) {
                walk(child, out, depth + 1);
            } else if (isAudio(child.getName())) {
                try {
                    out.add(copyToCache(child.getUri(), "upload-audio",
                            guessAudioExt(child.getUri())));
                } catch (Exception ignored) {
                    // 单个文件失败不阻塞整个文件夹
                }
            }
        }
    }

    private boolean isAudio(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : AUDIO_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用音频标签里的歌名补全/纠正曲目名。
     *
     * <p>与上传解耦：不再排在 3 条上传线程后面，所有新加入的曲目都会立刻排队读标签，
     * 用户手动改过名字（{@link AlbumDraft.Song#nameEdited}）时不再覆盖。
     */
    private void resolveSongName(final AlbumDraft.Song song) {
        if (song == null || song.nameEdited || song.file == null) {
            return;
        }
        metaExecutor.execute(() -> {
            final String cleaned = AlbumDraft.cleanSongName(readAudioTitle(song.file));
            if (cleaned.isEmpty() || cleaned.equals(song.name)) {
                return;
            }
            main.post(() -> {
                if (song.nameEdited || cleaned.equals(song.name)) {
                    return;
                }
                song.name = cleaned;
                adapter.refreshSong(song);
            });
        });
    }

    private void uploadSong(final AlbumDraft.Song song) {
        // 已有任务在跑（典型场景：替换正在上传的音频）：立刻置位取消，旧任务会在
        // 一个分块内退出，再由 finishUpload 用当前文件重新上传，不会并行也不会干等
        UploadTask running = activeUploads.get(song);
        if (running != null) {
            song.uploadToken++;
            song.pendingUpload = true;
            song.uploadStage = AlbumDraft.STAGE_QUEUED;
            song.uploadPercent = 0;
            song.uploadError = null;
            // 先打断旧任务，再撤掉它的聚合进度；它后续的进度回调也会因代次不符被忽略
            running.cancelled.set(true);
            endUploadTask(running.key);
            adapter.refreshSong(song);
            return;
        }
        // 一入队就标记「排队中」：批量导入时只有 4 条能立刻占用线程，
        // 其余必须让用户看到是在排队，而不是毫无反应地停在 0%
        final int token = ++song.uploadToken;
        final UploadTask task = new UploadTask();
        song.uploadStage = AlbumDraft.STAGE_QUEUED;
        song.uploadPercent = 0;
        song.uploadError = null;
        adapter.refreshSong(song);
        activeUploads.put(song, task);
        beginUploadTask(task.key);
        uploadExecutor.execute(() -> {
            // 任务可能在替换 / 移除之后才轮到线程：先固定本次要传的文件
            final File file = song.file;
            if (file == null || token != song.uploadToken || task.cancelled.get()) {
                main.post(() -> finishUpload(song, task, token));
                return;
            }
            main.post(() -> {
                if (token != song.uploadToken) {
                    return;
                }
                if (song.uploadStage == AlbumDraft.STAGE_QUEUED) {
                    song.uploadStage = AlbumDraft.STAGE_PREPARING;
                    adapter.refreshSong(song);
                }
            });
            try {
                NosUploader.NosFile nos = NosUploader.uploadAudio(getContext(), file,
                        song.mime, percent -> main.post(() -> {
                            if (token != song.uploadToken) {
                                return;
                            }
                            song.uploadStage = AlbumDraft.STAGE_UPLOADING;
                            song.uploadPercent = percent;
                            adapter.refreshSong(song);
                            updateUploadProgress(task.key, percent);
                        }), task.cancelled);
                main.post(() -> {
                    // 已被替换 / 移除：结果作废，不能覆盖新音频的上传状态
                    if (token == song.uploadToken) {
                        song.nosFile = nos;
                        song.uploadStage = AlbumDraft.STAGE_IDLE;
                        song.uploadPercent = 100;
                        song.uploadError = null;
                        adapter.refreshSong(song);
                        updateSongsEmpty();
                    }
                    finishUpload(song, task, token);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token == song.uploadToken) {
                        song.uploadStage = AlbumDraft.STAGE_IDLE;
                        song.uploadError = messageOf(e);
                        adapter.refreshSong(song);
                    }
                    finishUpload(song, task, token);
                });
            }
        });
    }

    /**
     * 撤掉一首歌当前在途的上传。
     *
     * <p>先置位 {@code cancelled} 让旧任务在一个分块内退出（否则新上传要等它把整首歌传完），
     * 代次（{@code uploadToken}）保证它残余的回调作废；旧缓存文件等任务退出后再删。
     */
    private void cancelUpload(AlbumDraft.Song song) {
        song.uploadToken++;
        song.pendingUpload = false;
        song.uploadStage = AlbumDraft.STAGE_IDLE;
        song.uploadPercent = 0;
        song.uploadError = null;
        UploadTask task = activeUploads.remove(song);
        File pending = song.recycleFile;
        song.recycleFile = null;
        if (task != null) {
            task.cancelled.set(true);
            // 任务还在读这个文件，交给它退出时删除
            song.recycleFile = song.file;
            endUploadTask(task.key);
        } else {
            deleteCachedCopy(song.file);
        }
        if (pending != null && pending != song.recycleFile) {
            deleteCachedCopy(pending);
        }
    }

    /** 收尾：撤掉本次任务的聚合进度，并处理替换 / 移除时挂起的旧文件与排队中的新上传。 */
    private void finishUpload(AlbumDraft.Song song, UploadTask task, int token) {
        endUploadTask(task.key);
        if (activeUploads.get(song) == task) {
            activeUploads.remove(song);
        }
        if (token == song.uploadToken) {
            return;
        }
        File recycle = song.recycleFile;
        song.recycleFile = null;
        deleteCachedCopy(recycle);
        // 替换音频时排下的新一轮上传：旧任务已退出，现在立刻开始
        if (song.pendingUpload) {
            song.pendingUpload = false;
            uploadSong(song);
        }
    }

    private void renumber() {
        int[] counters = new int[32];
        for (AlbumDraft.Song song : songs) {
            int disc = Math.max(1, Math.min(song.disc, 31));
            song.disc = disc;
            counters[disc]++;
            song.track = counters[disc];
        }
        // 只刷新已有行的曲序文案，保留插入 / 删除 / 拖拽的 ItemAnimator 动画
        songList.post(adapter::notifyTrackNumbersChanged);
    }

    /** 手动指定曲序：把歌曲移动到该碟内的目标位置，再统一重排。 */
    private void moveSongToTrack(AlbumDraft.Song song, int targetTrack) {
        songs.remove(song);
        List<Integer> discIndexes = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).disc == song.disc) {
                discIndexes.add(i);
            }
        }
        int position;
        if (targetTrack <= discIndexes.size()) {
            position = discIndexes.get(Math.max(0, targetTrack - 1));
        } else if (!discIndexes.isEmpty()) {
            position = discIndexes.get(discIndexes.size() - 1) + 1;
        } else {
            position = songs.size();
        }
        songs.add(Math.min(position, songs.size()), song);
        renumber();
        adapter.notifyDataSetChanged();
    }

    /** 修改碟序后把歌曲放到目标碟末尾。 */
    private void moveSongToDisc(AlbumDraft.Song song, int disc) {
        songs.remove(song);
        song.disc = disc;
        int insert = songs.size();
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).disc == disc) {
                insert = i + 1;
            }
        }
        songs.add(Math.min(insert, songs.size()), song);
        renumber();
        adapter.notifyDataSetChanged();
    }

    private void updateSongsEmpty() {
        songsEmpty.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------
    // 歌曲编辑
    // ------------------------------------------------------------------

    private void showSongEditor(final AlbumDraft.Song song) {
        // 弹窗前先收起输入法并清焦点：遮罩层出现后原输入框仍处于激活态，
        // 关闭弹窗时容易再次弹出键盘
        clearFieldFocus();
        View root = LayoutInflater.from(getContext()).inflate(R.layout.dialog_song_edit, null);
        TextInputEditText name = root.findViewById(R.id.et_song_name);
        TextInputEditText subtitle = root.findViewById(R.id.et_song_subtitle);
        TextInputEditText trans = root.findViewById(R.id.et_song_trans);
        final TextView discValue = root.findViewById(R.id.tv_song_disc);
        final TextView trackValue = root.findViewById(R.id.tv_song_track);
        final ChipGroup artistChips = root.findViewById(R.id.chip_song_artists);
        final ChipGroup removedChips = root.findViewById(R.id.chip_song_removed_artists);
        final TextView removedLabel = root.findViewById(R.id.tv_song_removed_label);
        final TextView audioFile = root.findViewById(R.id.tv_song_audio_file);
        final ChipGroup languageGroup = root.findViewById(R.id.chip_song_language);
        final ChipGroup tagGroup = root.findViewById(R.id.chip_song_tags);

        name.setText(song.name);
        subtitle.setText(song.subtitle);
        trans.setText(song.transName);
        // 与页面内的多行字段一致：内容超出 maxLines 后可在框内上下滑动查看
        InputScroll.enable(name, subtitle, trans);
        discValue.setText(String.valueOf(Math.max(1, song.disc)));
        trackValue.setText(String.valueOf(Math.max(1, song.track)));
        showAudioFileName(audioFile, song.file);

        final List<AlbumDraft.Artist> artists = new ArrayList<>(song.artists);
        final List<AlbumDraft.Artist> removed = new ArrayList<>(song.removedArtists);
        final Set<String> languages = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(song.language)) {
            for (String s : song.language.split(",")) {
                if (!s.trim().isEmpty()) {
                    languages.add(s.trim());
                }
            }
        }
        final Set<AlbumDraft.Tag> tags = new LinkedHashSet<>(song.tags);

        final Runnable[] renderRemoved = new Runnable[1];
        final ArtistRemove onArtistRemoved = artist -> {
            if (!removed.contains(artist)) {
                removed.add(0, artist);
            }
            renderRemoved[0].run();
        };
        renderRemoved[0] = () -> {
            MotionManager.beginAutoTransition(getContext(), removedChips);
            removedChips.removeAllViews();
            boolean has = !removed.isEmpty();
            removedLabel.setVisibility(has ? View.VISIBLE : View.GONE);
            removedChips.setVisibility(has ? View.VISIBLE : View.GONE);
            for (final AlbumDraft.Artist artist : removed) {
                Chip chip = newOutlinedChip(artist.name);
                chip.setChipIcon(ContextCompat.getDrawable(getContext(), R.drawable.ic_add));
                chip.setChipIconVisible(true);
                chip.setOnClickListener(v -> {
                    HapticsManager.tap(v);
                    if (!artists.contains(artist)) {
                        artists.add(artist);
                    }
                    removed.remove(artist);
                    renderArtistChips(artistChips, artists, onArtistRemoved);
                    renderRemoved[0].run();
                });
                removedChips.addView(chip);
            }
        };
        renderArtistChips(artistChips, artists, onArtistRemoved);
        renderRemoved[0].run();
        final boolean[] langExpanded = {false};
        final Runnable[] bindLanguages = new Runnable[1];
        bindLanguages[0] = () -> ChipOptions.bindLanguages(languageGroup, languages,
                langExpanded[0], () -> {
                    langExpanded[0] = !langExpanded[0];
                    bindLanguages[0].run();
                });
        bindLanguages[0].run();

        final boolean[] songTagsExpanded = {false};
        final Runnable[] bindSongTags = new Runnable[1];
        bindSongTags[0] = () -> bindStyleTags(tagGroup, tags, songTagsExpanded[0], () -> {
            songTagsExpanded[0] = !songTagsExpanded[0];
            bindSongTags[0].run();
        });
        bindSongTags[0].run();

        final AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.au_dialog_song_title)
                .setView(root)
                .setPositiveButton(R.string.au_action_save, null)
                .setNegativeButton(R.string.au_action_cancel, null)
                .setNeutralButton(R.string.au_action_remove_song, null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String newName = text(name);
                if (TextUtils.isEmpty(newName)) {
                    toast(getContext().getString(R.string.au_song_name));
                    return;
                }
                if (artists.isEmpty()) {
                    toast(getContext().getString(R.string.au_song_artists));
                    return;
                }
                if (languages.isEmpty()) {
                    toast(getContext().getString(R.string.au_song_language));
                    return;
                }
                song.name = newName;
                song.nameEdited = true;
                song.subtitle = text(subtitle);
                song.transName = text(trans);
                song.artists.clear();
                song.artists.addAll(artists);
                song.removedArtists.clear();
                song.removedArtists.addAll(removed);
                song.language = TextUtils.join(",", languages);
                song.tags.clear();
                song.tags.addAll(tags);
                adapter.refreshSong(song);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new MaterialAlertDialogBuilder(getContext())
                            .setMessage(R.string.au_action_remove_song)
                            .setPositiveButton(R.string.au_action_remove, (dd, w) -> {
                                int index = songs.indexOf(song);
                                if (index >= 0) {
                                    songs.remove(index);
                                    adapter.notifyItemRemoved(index);
                                    renumber();
                                    // 正在上传的也要立刻退出上传态：作废旧任务，
                                    // 缓存文件等它退出后再删，避免边传边删
                                    cancelUpload(song);
                                }
                                updateSongsEmpty();
                                dialog.dismiss();
                            })
                            .setNegativeButton(R.string.au_action_cancel, null)
                            .show());
        });
        root.findViewById(R.id.btn_song_add_artist).setOnClickListener(v ->
                ArtistPickerDialog.show(getContext(), new ArrayList<>(removed), artist -> {
                    if (!artists.contains(artist)) {
                        artists.add(artist);
                    }
                    removed.remove(artist);
                    renderArtistChips(artistChips, artists, onArtistRemoved);
                    renderRemoved[0].run();
                }));
        root.findViewById(R.id.row_song_disc).setOnClickListener(v ->
                pickNumber(getContext().getString(R.string.au_song_disc), 1, 9,
                        Math.max(1, song.disc), value -> {
                            moveSongToDisc(song, value);
                            discValue.setText(String.valueOf(value));
                        }));
        root.findViewById(R.id.row_song_track).setOnClickListener(v ->
                pickNumber(getContext().getString(R.string.au_song_track), 1, 99,
                        Math.max(1, song.track), value -> {
                            moveSongToTrack(song, value);
                            trackValue.setText(String.valueOf(value));
                        }));
        root.findViewById(R.id.btn_song_replace_audio).setOnClickListener(v -> {
            replacingSong = song;
            dialog.dismiss();
            replaceAudioLauncher.launch(new String[]{"audio/*"});
        });
        // 移除艺人后自动回到候选首位（由 renderArtistChips 的移除回调维护）
        dialog.show();
    }

    private void replaceAudio(AlbumDraft.Song song, Uri uri) {
        try {
            File file = copyToCache(uri, "upload-audio", guessAudioExt(uri));
            File previous = song.file;
            boolean inFlight = activeUploads.containsKey(song);
            song.file = file;
            song.mime = guessMime(file.getName());
            song.nosFile = null;
            song.uploadError = null;
            // 换音频后按新文件更名：旧名字属于旧音频，之前强制保留会出现「替换后不更名」
            song.name = AlbumDraft.cleanSongName(stripExt(file.getName()));
            song.nameEdited = false;
            if (inFlight) {
                // 旧任务还在读旧文件：等它退出后再删，期间也不会重复占带宽
                File pending = song.recycleFile;
                if (pending != null && pending != previous) {
                    deleteCachedCopy(pending);
                }
                song.recycleFile = previous;
            } else {
                deleteCachedCopy(previous);
            }
            adapter.refreshSong(song);
            resolveSongName(song);
            uploadSong(song);
        } catch (Exception e) {
            toast(getContext().getString(R.string.au_pick_audio_failed));
        }
    }

    private void pickNumber(String title, int min, int max, int current, NumberPick onPick) {
        String[] items = new String[max - min + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = String.valueOf(min + i);
        }
        // 音轨号最多 99 项，用可滚动的底部抽屉而不是高对话框
        SheetList.singleChoice(getContext(), title, items, current - min,
                which -> onPick.onPick(min + which));
    }

    private interface NumberPick {
        void onPick(int value);
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /** 顶栏「提交审核」入口：与表单底部按钮共用同一套校验与提交逻辑。 */
    void submitFromToolbar() {
        submit();
    }

    private void submit() {
        if (submitting) {
            return;
        }
        invalidFieldId = 0;
        clearFieldErrors();
        String error = validate();
        if (error != null) {
            showFieldError(error);
            return;
        }
        if (targetArtist == null) {
            return;
        }
        final long artistId = targetArtist.id;
        // 表单值统一在主线程取快照：后台线程读 EditText 不符合线程模型，
        // 输入法尚无提交组合串时还可能取到空值（专辑介绍曾因此提交为空）
        final String albumNameValue = text(albumName);
        final String subtitleValue = text(albumSubtitle);
        final String transValue = text(albumTrans);
        final String companyValue = text(company);
        final String languageValue = TextUtils.join(",", selectedLanguages);
        final List<AlbumDraft.Tag> tagSnapshot = new ArrayList<>(selectedTags);
        final String sourceValue = text(source);
        final String introValue = text(intro);
        final long publishTimeValue = publishTime == null ? 0 : publishTime;
        final List<AlbumDraft.Song> songSnapshot = new ArrayList<>(songs);
        // 提交期间用户仍可能改动表单，先把本次提交涉及的艺人快照下来，
        // 成功清空表单时据此恢复「可重新添加」待选项，避免目标艺人凭空消失
        final AlbumDraft.Artist submittedTarget = targetArtist;
        final List<AlbumDraft.Artist> submittedAlbumArtists = new ArrayList<>(albumArtists);
        submitting = true;
        submitButton.setEnabled(false);
        MotionManager.animateVisibility(getContext(), progress, true);
        statusView.setText(R.string.au_submitting);
        MotionManager.fadeSlideIn(getContext(), statusView);

        executor.execute(() -> {
            try {
                // 网页百科把「专辑介绍」提交为 production；notes 是「补充备注」，只在审核端可见。
                // 此前把介绍写进 notes，审核通过后专辑简介始终为空（album description 为空）。
                JSONObject vo = AlbumDraft.toAlbumVo(albumNameValue,
                        subtitleValue, selectedAlbumType, transValue,
                        submittedAlbumArtists, companyValue, publishTimeValue,
                        languageValue, tagSnapshot, introValue, songSnapshot,
                        sourceValue, "", coverRawNos, coverSquareNos);
                NetEaseWikiClient.submitAlbum(getContext(), artistId, vo, null);
                main.post(() -> {
                    submitting = false;
                    submitButton.setEnabled(true);
                    MotionManager.animateVisibility(getContext(), progress, false);
                    // 发行公司写入历史，供下次输入联想
                    WikiPrefs.rememberCompany(getContext(), companyValue);
                    statusView.setText(R.string.au_submit_ok);
                    MotionManager.fadeSlideIn(getContext(), statusView);
                    showBanner(true, getContext().getString(R.string.au_audit_ok_title),
                            getContext().getString(R.string.au_audit_ok_desc));
                    clearFormAfterSuccess(submittedTarget, submittedAlbumArtists);
                });
            } catch (Exception e) {
                main.post(() -> {
                    submitting = false;
                    submitButton.setEnabled(true);
                    MotionManager.animateVisibility(getContext(), progress, false);
                    String message = getContext().getString(R.string.au_submit_failed,
                            messageOf(e));
                    statusView.setText(message);
                    MotionManager.fadeSlideIn(getContext(), statusView);
                    toast(message);
                    showBanner(false, getContext().getString(R.string.au_audit_fail_title),
                            messageOf(e));
                });
            }
        });
    }

    /**
     * 审核提交成功后清空表单。
     *
     * <p>目标艺人会转入「可重新添加」待选列表（与原 APK 移除艺人后保留待选项一致），
     * 既避免重复提交，也方便用户继续补充同一艺人的其它专辑。
     */
    private void clearFormAfterSuccess(AlbumDraft.Artist target,
                                       List<AlbumDraft.Artist> submittedAlbumArtists) {
        restoreSubmittedArtists(target, submittedAlbumArtists);
        targetArtist = null;
        suppressArtistSearch = true;
        targetInput.setText("");
        suppressArtistSearch = false;
        artistSearchSeq++;
        showArtistResults(new ArrayList<>(), false);
        renderTargetChip();
        albumArtists.clear();
        renderAlbumArtistChips();

        // 提交成功后本页复制的封面临时文件已无用，删除避免缓存无限增长
        deleteCachedCopy(coverRawFile);
        deleteCachedCopy(coverSquareFile);
        coverRawFile = null;
        coverSquareFile = null;
        coverRawNos = null;
        coverSquareNos = null;
        coverError = null;
        coverUploading = false;
        int coverPad = getResources().getDimensionPixelSize(R.dimen.spacing_lg);
        coverView.setPadding(coverPad, coverPad, coverPad, coverPad);
        coverView.setImageResource(R.drawable.ic_image_muted);
        coverState.setVisibility(View.GONE);
        coverButton.setText(R.string.au_cover_add);

        for (AlbumDraft.Song song : songs) {
            deleteCachedCopy(song.file);
        }
        songs.clear();
        adapter.notifyDataSetChanged();
        renumber();
        updateSongsEmpty();

        albumName.setText("");
        albumSubtitle.setText("");
        albumTrans.setText("");
        company.setText("");
        source.setText("");
        intro.setText("");
        selectedAlbumType = AlbumDraft.ALBUM_TYPES[0];
        albumType.setText(selectedAlbumType, false);
        publishTime = null;
        publishValue.setText(R.string.au_publish_date_hint);
        selectedLanguages.clear();
        buildLanguageChips();
        selectedTags.clear();
        buildTagChips();
        agreement.setChecked(false);
        refreshCompanyHistory();
        scroll.scrollTo(0, 0);
    }

    /**
     * 提交成功后，把这次涉及的艺人恢复为「可重新添加」候选，
     * 并强制把目标艺人放在首位。
     */
    private void restoreSubmittedArtists(AlbumDraft.Artist target,
                                         List<AlbumDraft.Artist> submittedAlbumArtists) {
        if (submittedAlbumArtists != null) {
            for (AlbumDraft.Artist artist : submittedAlbumArtists) {
                keepAddable(artist);
            }
        }
        if (target == null || TextUtils.isEmpty(target.name)) {
            return;
        }
        keepTargetSuggestion(target);
    }

    /** 页面顶部的应用内消息：成功 / 失败都用 MD3 语义色容器强调。 */
    private void showBanner(boolean success, String title, String description) {
        if (bannerCard == null) {
            return;
        }
        int bgAttr = success
                ? com.google.android.material.R.attr.colorTertiaryContainer
                : com.google.android.material.R.attr.colorErrorContainer;
        int fgAttr = success
                ? com.google.android.material.R.attr.colorOnTertiaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer;
        int bg = MaterialColors.getColor(bannerCard, bgAttr);
        int fg = MaterialColors.getColor(bannerCard, fgAttr);
        bannerCard.setCardBackgroundColor(bg);
        bannerTitle.setText(title);
        bannerTitle.setTextColor(fg);
        bannerDesc.setText(description);
        bannerDesc.setTextColor(fg);
        bannerIcon.setImageResource(success ? R.drawable.ic_check_circle : R.drawable.ic_wiki_error);
        bannerIcon.setImageTintList(ColorStateList.valueOf(fg));
        MotionManager.animateVisibility(getContext(), bannerCard, true);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
    }

    @Nullable
    private String validate() {
        Context ctx = getContext();
        if (targetArtist == null) {
            return invalid(ctx, R.string.au_err_target_artist, R.id.et_au_target);
        }
        if (TextUtils.isEmpty(text(albumName))) {
            return invalid(ctx, R.string.au_err_album_name, R.id.et_au_name);
        }
        if (albumArtists.isEmpty()) {
            return invalid(ctx, R.string.au_err_album_artist, R.id.chip_au_album_artists);
        }
        if (TextUtils.isEmpty(text(company))) {
            return invalid(ctx, R.string.au_err_company, R.id.et_au_company);
        }
        if (publishTime == null) {
            return invalid(ctx, R.string.au_err_publish, R.id.row_au_publish);
        }
        if (selectedLanguages.isEmpty()) {
            return invalid(ctx, R.string.au_err_language, R.id.chip_au_language);
        }
        if (TextUtils.isEmpty(selectedAlbumType)) {
            return invalid(ctx, R.string.au_err_type, R.id.act_au_type);
        }
        if (songs.isEmpty()) {
            return invalid(ctx, R.string.au_err_songs, R.id.rv_au_songs);
        }
        if (selectedTags.size() > 3) {
            return invalid(ctx, R.string.au_err_tags, R.id.chip_au_tags);
        }
        if (TextUtils.isEmpty(text(source))) {
            return invalid(ctx, R.string.au_err_source, R.id.et_au_source);
        }
        for (AlbumDraft.Song song : songs) {
            if (song.uploading()) {
                return invalid(ctx, R.string.au_err_audio_uploading, R.id.rv_au_songs);
            }
            if (!TextUtils.isEmpty(song.uploadError)) {
                return invalid(ctx.getString(R.string.au_err_audio_failed, song.uploadError),
                        R.id.rv_au_songs);
            }
            if (!song.uploaded() || TextUtils.isEmpty(song.name)
                    || song.artists.isEmpty() || TextUtils.isEmpty(song.language)) {
                return invalid(ctx, R.string.au_err_song_fields, R.id.rv_au_songs);
            }
        }
        if (coverUploading || (coverSquareFile != null && coverSquareNos == null)) {
            return invalid(TextUtils.isEmpty(coverError)
                    ? ctx.getString(R.string.au_err_cover_uploading)
                    : ctx.getString(R.string.au_err_cover_failed, coverError), R.id.btn_au_cover);
        }
        if (!agreement.isChecked()) {
            return invalid(ctx, R.string.au_err_agreement, R.id.cb_au_agree);
        }
        if (!WikiPrefs.hasCookie(getContext())) {
            return invalid(ctx, R.string.au_err_not_login, 0);
        }
        return null;
    }

    /** 记录出错字段并返回原提示文案（校验语义保持不变）。 */
    private String invalid(Context ctx, @StringRes int messageRes, @IdRes int fieldId) {
        invalidFieldId = fieldId;
        return ctx.getString(messageRes);
    }

    private String invalid(CharSequence message, @IdRes int fieldId) {
        invalidFieldId = fieldId;
        return message == null ? "" : message.toString();
    }

    /**
     * 校验失败：滚动定位到出错字段，文本字段额外显示行内错误。
     *
     * <p>此前只弹 Toast 并把文案写进底部状态栏，长表单里用户仍不知道错在哪一项；
     * 现在按字段定位，且行内错误在用户开始输入时自动清除。
     */
    private void showFieldError(String message) {
        statusView.setText(message);
        View anchor = invalidFieldId == 0 ? null : findViewById(invalidFieldId);
        if (anchor == null) {
            toast(message);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            return;
        }
        TextInputLayout layout = fieldLayoutOf(invalidFieldId);
        if (layout != null) {
            layout.setError(message);
            anchor = layout;
            View field = findViewById(invalidFieldId);
            if (field != null) {
                field.requestFocus();
            }
        } else {
            toast(message);
        }
        final View target = anchor;
        scroll.post(() -> target.requestRectangleOnScreen(
                new Rect(0, 0, target.getWidth(), target.getHeight()), true));
    }

    private void clearFieldErrors() {
        for (int id : FIELD_LAYOUT_IDS) {
            TextInputLayout layout = findViewById(id);
            if (layout != null) {
                layout.setError(null);
            }
        }
    }

    /** 文本字段 → 外层 TextInputLayout；非文本字段返回 null。 */
    @Nullable
    private TextInputLayout fieldLayoutOf(@IdRes int fieldId) {
        int layoutId;
        if (fieldId == R.id.et_au_target) {
            layoutId = R.id.til_au_target;
        } else if (fieldId == R.id.et_au_name) {
            layoutId = R.id.til_au_name;
        } else if (fieldId == R.id.et_au_company) {
            layoutId = R.id.til_au_company;
        } else if (fieldId == R.id.et_au_source) {
            layoutId = R.id.til_au_source;
        } else if (fieldId == R.id.act_au_type) {
            layoutId = R.id.til_au_type;
        } else {
            return null;
        }
        return findViewById(layoutId);
    }

    /** 用户开始修改时立即清掉行内错误，避免错误提示滞留。 */
    private void bindFieldErrorClear() {
        clearErrorOnEdit(targetInput);
        clearErrorOnEdit(albumName);
        clearErrorOnEdit(company);
        clearErrorOnEdit(source);
        clearErrorOnEdit(albumType);
    }

    private void clearErrorOnEdit(@Nullable EditText field) {
        if (field == null) {
            return;
        }
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                TextInputLayout layout = fieldLayoutOf(field.getId());
                if (layout != null && layout.getError() != null) {
                    layout.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 在目录内取一个不冲突的文件名：重名时追加 (2)、(3)…，保留扩展名。 */
    private File uniqueFile(File dir, String name) {
        File out = new File(dir, name);
        if (!out.exists()) {
            return out;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String tail = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        while (out.exists() && index < 999) {
            index++;
            out = new File(dir, stem + " (" + index + ")" + tail);
        }
        return out;
    }

    private File copyToCache(Uri uri, String dirName, String ext) throws Exception {
        File dir = new File(getContext().getCacheDir(), dirName);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建缓存目录");
        }
        String name = displayName(uri);
        if (TextUtils.isEmpty(name)) {
            name = "file-" + System.currentTimeMillis();
        }
        if (!name.contains(".")) {
            name = name + "." + ext;
        }
        // 保留原始文件名（歌名与「当前文件」都要靠它），重名时用序号兜底，
        // 不再拼毫秒时间戳，否则会把 "1792938469390-" 带进歌名
        File out = uniqueFile(dir, name);
        ContentResolver resolver = getContext().getContentResolver();
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IllegalStateException("无法读取文件");
            }
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
        } catch (Exception e) {
            // 复制失败时清掉半截文件，避免被当成有效音频列出来
            out.delete();
            throw e;
        }
        return out;
    }

    /**
     * 删除本页复制到缓存里的临时副本。
     *
     * <p>只处理 {@code cacheDir} 之下的文件：从「声云下载」交接来的音频位于下载目录，
     * 属于用户资产，不能随表单一起删除。
     */
    private void deleteCachedCopy(@Nullable File file) {
        if (file == null) {
            return;
        }
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            String cache = context.getCacheDir().getCanonicalPath() + File.separator;
            if (file.getCanonicalPath().startsWith(cache)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
            // 路径解析失败时保持原文件，宁可多占空间也不误删
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // 部分 provider 不支持查询
        }
        return uri.getLastPathSegment();
    }

    private String guessAudioExt(Uri uri) {
        String name = displayName(uri);
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                return name.substring(dot + 1);
            }
        }
        String type = getContext().getContentResolver().getType(uri);
        if (type != null && type.contains("mpeg")) {
            return "mp3";
        }
        return "m4a";
    }

    private String guessImageExt(Uri uri) {
        String type = getContext().getContentResolver().getType(uri);
        if (type != null && type.contains("png")) {
            return "png";
        }
        return "jpg";
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
            return "audio/ogg";
        }
        return "audio/mp4";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String text(EditText editText) {
        CharSequence value = editText.getText();
        return value == null ? "" : value.toString().trim();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /**
     * 输入框有焦点但输入法是收起的状态下离开应用（选文件、切到别的应用等），
     * 回来时系统会因为「输入框仍聚焦、仍有输入连接」把输入法重新拉起。
     *
     * <p>若等它拉起再收起，会看到一次输入法的弹出 / 消失动画；这里改成离开前就让
     * 输入框暂时失去焦点——没有输入连接，系统便不会恢复输入法；回来后再静默把焦点
     * 还给它（程序化 requestFocus 不会弹出输入法）。离开前正在打字则完全不动。
     */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            if (isImeVisible()) {
                return;
            }
            View focused = findFocus();
            if (focused instanceof EditText) {
                focusOnReturn = focused;
                scrollYOnFocusLoss = scroll == null ? 0 : scroll.getScrollY();
                focused.clearFocus();
            }
            return;
        }
        final View restore = focusOnReturn;
        if (restore == null) {
            return;
        }
        focusOnReturn = null;
        final int scrollY = scrollYOnFocusLoss;
        // 等窗口焦点分发完成后再还焦点，避免被系统的焦点恢复流程覆盖
        post(() -> {
            restore.requestFocus();
            if (scroll == null) {
                return;
            }
            // 还焦点时 NestedScrollView 会把输入框滚进可视区，整页会被强制跳到同一位置；
            // 焦点照还，滚动位置随后还原（焦点滚动可能延后到下一次布局，再兜底一次）。
            scroll.scrollTo(0, scrollY);
            scroll.post(() -> scroll.scrollTo(0, scrollY));
        });
    }

    /**
     * 输入法当前是否可见。
     *
     * <p>输入法内边距只在 Android 11 及以上单独上报；更低版本无法可靠判断，
     * 直接按「可见」处理，不做任何处理，避免误收用户正在使用的输入法。
     */
    private boolean isImeVisible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        return insets == null || insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getContext()
                            .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        } catch (Exception ignored) {
            // 输入法不可用时无需处理
        }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void toast(String text) {
        Feedback.show(this, text);
    }
}
