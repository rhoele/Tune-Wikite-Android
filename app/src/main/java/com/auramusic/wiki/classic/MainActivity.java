package com.auramusic.wiki.classic;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.BackEventCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 主界面（单 Activity 宿主，对应基准的单页内联架构）。
 *
 * 并存融合后的两个顶层目的地：
 *   1. 歌曲百科查询（基准 F1–F18 全量能力）
 *   2. 我的曲库贡献（对齐原 APK：接口通道 + 账号卡片 + 分类 Tab + 审核状态筛选 + 页码分页）
 *
 * 后台审核轮询通知（C4/C5）由 {@link AuditPollingService} 承担，与界面解耦。
 */
public class MainActivity extends BaseActivity {

    public static final String EXTRA_DESTINATION = "extra_destination";
    /** 推送点击携带的歌曲查询词（route=wiki 时使用） */
    public static final String EXTRA_SONG_QUERY = "extra_song_query";
    /** 从「未通过原因」长按跳转：目标贡献的名称（专辑名） */
    public static final String EXTRA_FOCUS_ALBUM = "extra_focus_album";
    /** 从「未通过原因」长按跳转：通知时间，用于在同名条目里挑最接近的一条 */
    public static final String EXTRA_FOCUS_TIME = "extra_focus_time";
    /** 从「未通过原因」长按跳转：项目类型（{@code RejectArchive.CAT_*}），决定扫描哪个贡献分类 */
    public static final String EXTRA_FOCUS_CATEGORY = "extra_focus_category";
    public static final int DEST_WIKI = 0;
    public static final int DEST_CONTRIBUTIONS = 1;
    /** 声云资源下载（新） */
    public static final int DEST_DOWNLOAD = 2;
    /** 专辑上传（新，对应原 APK「补充缺失专辑」） */
    public static final int DEST_ALBUM_UPLOAD = 3;

    private static final int REQUEST_NOTIFICATION = 1001;

    /** 「我的贡献」日志 TAG，便于线上排查闪退与空列表问题 */
    private static final String TAG = "WikiContrib";

    private static final int STATE_IDLE = 0;
    private static final int STATE_CONTENT = 1;
    private static final int STATE_LOADING = 2;
    private static final int STATE_EMPTY = 3;
    private static final int STATE_ERROR = 4;

    /**
     * 检索时单页扫描条数（比展示分页大，减少往返次数）。
     * 服务端接口没有关键词参数，只能在客户端全量扫描后本地过滤。
     *
     * <p>实测：400 条/页服务端会完整返回；800 条/页会被截断（扫描总数变少），
     * 因此这里取 400 —— 3000+ 条的账号从 37 次往返降到 10 次。
     */
    private static final int SEARCH_SCAN_PAGE_SIZE = 400;
    /**
     * 检索扫描的页数上限（400 × 60 = 24000 条）。
     *
     * <p>此前是 30 页 × 100 条（3000 条），对 3000+ 条的账号会漏掉早期条目；
     * 并行扫描后多翻一倍页数的耗时仍在秒级。
     */
    private static final int SEARCH_SCAN_MAX_PAGES = 60;

    /**
     * 底栏尺寸令牌（dp）。与 {@code dimens.xml} 中的 nav_float_* 保持一致：
     * 高度 60dp / 左右 20dp / 底部 8dp / 圆角 28dp，仍远大于 48dp 最小触控热区。
     */
    private static final int NAV_MARGIN_H_DP = 20;
    private static final int NAV_MARGIN_BOTTOM_DP = 8;
    private static final int NAV_RADIUS_DP = 28;

    private Toolbar toolbar;
    private ExpressiveNavBar bottomNav;
    /** 顶栏「提交审核」动作（仅专辑上传页可见） */
    private MenuItem submitAlbumItem;
    /** 顶栏「审核消息」聚合入口（未读角标） */
    private MenuItem messagesItem;
    private BadgeDrawable messageBadge;
    /**
     * 角标当前挂载的 ActionView。
     *
     * <p>不能用布尔标记「挂过一次」：{@code switchDestination()} 会
     * {@code invalidateOptionsMenu()}，菜单清空重建后 ActionView 是新对象，
     * 旧角标留在已脱离窗口的视图上，表现为「有未读却没有角标」。
     */
    private View badgeAnchor;
    /** 锚点未就绪时的延后重试标记，避免重复 post */
    private boolean badgeAttachPending;
    /** 挂载重试次数上限：菜单项始终不可用时放弃，避免无限 post */
    private static final int MAX_BADGE_ATTACH_RETRIES = 3;
    private int badgeAttachRetries;
    /** 顶栏上传进度（封面 / 歌曲上传时显示） */
    private LinearProgressIndicator toolbarUploadProgress;
    /** 底部导航栏的胶囊外壳，承载「悬浮 / 贴底」两种样式的边距、圆角与海拔 */
    private MaterialCardView navShell;
    private View pageWiki;
    private WikiQueryView wikiView;
    private View pageContributions;
    private View pageDownload;
    private SoundCloudDownloadView downloadView;
    private View pageAlbumUpload;
    private AlbumUploadView albumUploadView;

    /** 内容宿主：两个目的地页面都在其中，底栏以覆盖方式叠在它之上 */
    private FrameLayout contentHost;
    /** 底部悬浮覆盖层（玻璃背景 + 页面选择器 + 底栏），不占据内容高度 */
    private FrameLayout bottomOverlay;
    /** 覆盖层内真正承载控件的列（上下内边距在此处吸收系统栏 / 输入法内边距） */
    private View overlayContent;
    /** 底栏与页面选择器共用的玻璃模糊层 */
    private BackdropBlurView overlayBlur;
    /** 覆盖层实测高度（px），用于给滚动内容补等量的底部留白 */
    private int overlayHeight;
    /** 输入法抬升量（px） */
    private int overlayBottomInset;
    /** 系统导航栏底部高度（px）：悬浮态作为下外边距 */
    private int navBarInset;
    /** 底部手势安全高度（px）：贴底态作为外壳下内边距，避免文字压到手势条 */
    private int navBottomSafeInset;
    /** 页面选择器当前是否已展开，用于滑入 / 滑出动画去重 */
    private boolean pagerShown;
    /** 用户主动翻页（上一页 / 下一页 / 首末页 / 页码面板）时置位，用于触发列表淡入淡出 */
    private boolean pageNavPending;
    /** 列表当前是否处于「翻页压暗」状态，避免重复触发 */
    private boolean listDimmed;
    /** 超限手势起点与「按下时是否位于列表顶部」 */
    private float touchDownY;
    private boolean scrollAtTopOnDown;
    private ValueAnimator overscrollAnimator;
    /** 页面选择器与底栏合并 / 分离时的上圆角动画 */
    private ValueAnimator navCornerAnimator;
    private int navTopCornerPx = -1;

    private RadioGroup groupTransport;
    private TabLayout tabLayout;
    private ChipGroup chipStatus;
    private RecyclerView recyclerView;
    private ContributionAdapter adapter;
    /** 承载「顶部信息 + 贡献列表」的可滚动容器（需求 2） */
    private NestedScrollView contribScroll;
    /** 原生下拉刷新外壳（需求 2）：圆形旋转指示器 + 触发时同步振动反馈 */
    private SwipeRefreshLayout contribSwipe;

    /** 可整体隐藏的全宽卡片（需求 3 / 5 / 6）：隐藏后下方内容由 LinearLayout 自动上移填充 */
    private View transportCard;
    private View accountTitle;
    private View accountCard;
    private View searchCard;

    private View pagerBar;
    private MaterialButton btnPagePrev;
    private MaterialButton btnPageNext;
    private ImageButton btnPageFirst;
    private ImageButton btnPageLast;
    private TextView tvPageInfo;

    // 贡献信息搜索（需求 5）：关键词模糊匹配 + 提交日期范围筛选
    private TextInputLayout tilSearchKeyword;
    private EditText etSearchKeyword;
    private View rowSearchDate;
    private TextView tvSearchDate;
    private MaterialButton btnSearchApply;
    private MaterialButton btnSearchReset;
    private TextView tvSearchResult;

    // 搜索卡片双布局：普通竖向 / 精简单行（两套控件同时存在，按设置切换显隐）
    private View searchNormal;
    private View searchCompact;
    private TextInputLayout tilSearchKeywordCompact;
    private EditText etSearchKeywordCompact;
    private View btnSearchDateCompact;
    private View btnSearchApplyCompact;
    private boolean searchCompactMode;

    /** 当前生效的关键词（小写前的原文） */
    private String searchKeyword = "";
    /** 当前生效的提交日期范围（本地日零点 / 当日 23:59:59.999，0 表示不限） */
    private long searchDateFrom;
    private long searchDateTo;
    /** 本页原始条目（未过滤），非检索模式下由服务端分页提供 */
    private List<Contribution> lastPageItems = Collections.emptyList();

    /**
     * 检索扫描缓存：一次全量扫描后复用，避免每次改条件都重新翻页。
     * 分类 / 状态 / 通道 / 凭据变化时置空（见 {@link #reload()}）。
     */
    private List<Contribution> scanCache;

    /**
     * 已取过的服务端页：上一页 / 下一页来回切换时直接复用，不再走网络。
     *
     * <p>按访问顺序淘汰，只保留最近 8 页（约 160 条），内存占用可忽略。
     */
    private final LinkedHashMap<String, NetEaseWikiClient.Page> pageCache =
            new LinkedHashMap<String, NetEaseWikiClient.Page>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, NetEaseWikiClient.Page> eldest) {
                    return size() > 8;
                }
            };
    /** 当前是否处于检索结果模式：此时翻页走本地命中集，不再请求服务端 */
    private boolean searching;
    /** 检索命中的全部条目（已按关键词与日期范围过滤），分页由其规模决定 */
    private List<Contribution> searchMatches = Collections.emptyList();

    private ImageView ivAvatar;
    private TextView tvAccountName;
    private TextView tvAccountUid;
    private TextView tvAccountStats;
    private MaterialButton btnAccountAction;

    private View stateLoading;
    private View stateEmpty;
    private View stateError;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private TextView errorMessage;
    private MaterialButton errorAction;

    /** 分类默认「全部」，取值见 {@link NetEaseWikiClient#TYPE_VALUES} */
    private int currentType = NetEaseWikiClient.TYPE_VALUES[0];
    /** 审核状态筛选，null 表示全部 */
    private Integer currentStatus = NetEaseWikiClient.STATUS_VALUES[0];

    private int destination = DEST_WIKI;
    private int currentStateTag = STATE_IDLE;

    private Http.CancelToken inflight;
    private int generation;
    private int statsGeneration;

    /** 统计数字的计数动画，以及上一次展示值（-1 表示尚未展示过） */
    private ValueAnimator statsCounter;
    private int statsUgcShown = -1;
    private int statsShellShown = -1;

    /** 当前页码，从 1 开始（与原 APK 一致，offset = (page - 1) × 20） */
    private int currentPage = 1;
    private int totalPages = 1;

    /** 错误态动作：true 表示「去登录」，false 表示「重试」 */
    private boolean errorActionGoLogin;
    /** 上次加载所用的凭据快照，用于识别登录/退出后需要重新拉取 */
    private String cookieSnapshot = "";
    /** 上次加载时的封面开关快照，设置里改过则需要重新应用 */
    private boolean coversSettingSnapshot = true;
    /** 顶部卡片显隐快照（需求 3 / 6）：设置里改过时在 onResume 重新排布 */
    private boolean transportCardSnapshot = true;
    private boolean accountCardSnapshot = true;
    private boolean searchCardSnapshot = true;

    /**
     * 显示设置里的卡片开关：改完立刻应用。
     *
     * <p>只依赖 onResume 会有明显延迟（设置页退出动画、返回栈恢复都可能晚于用户的
     * 预期），这里直接监听偏好变化，在开关落库的同一帧把显隐刷新掉。
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener displayPrefListener =
            (prefs, key) -> {
                if (WikiPrefs.KEY_SHOW_TRANSPORT_CARD.equals(key)
                        || WikiPrefs.KEY_SHOW_ACCOUNT_CARD.equals(key)
                        || WikiPrefs.KEY_SHOW_SEARCH.equals(key)) {
                    applyCardVisibility();
                }
            };

    /**
     * 滚动状态持久化（需求 2）：本次加载开始前的滚动位置。
     * 加载过程中不再清空列表/隐藏列表区，渲染完成后再按此值校正，
     * 使点击任意交互按钮（分类、状态、通道、分页…）后仍停留在原位置。
     */
    private int pendingScrollY = -1;

    /** 登录页返回即重新拉取贡献记录，避免用户手动切页 */
    private final ActivityResultLauncher<Intent> loginLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    cookieSnapshot = SessionManager.cookie(this);
                    // 登录后重新登记推送令牌（携带新的 userId）
                    FcmManager.ensureToken(this);
                    reload();
                }
            });

    /** 预见性返回回调：仅在「我的贡献」目的地启用 */
    private OnBackPressedCallback backCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        bottomNav = findViewById(R.id.bottom_nav);
        navShell = findViewById(R.id.nav_shell);
        pageWiki = findViewById(R.id.wiki_view);
        wikiView = (WikiQueryView) pageWiki;
        pageContributions = findViewById(R.id.page_contributions);
        pageDownload = findViewById(R.id.page_download);
        downloadView = (SoundCloudDownloadView) pageDownload;
        pageAlbumUpload = findViewById(R.id.page_album_upload);
        albumUploadView = (AlbumUploadView) pageAlbumUpload;
        downloadView.setHandoffListener(() -> {
            selectBottomNavItem(DEST_ALBUM_UPLOAD);
            switchDestination(DEST_ALBUM_UPLOAD);
        });

        // 顶栏上传进度：封面 / 歌曲在途时显示实时百分比，全部结束后自动收起
        toolbarUploadProgress = findViewById(R.id.pb_toolbar_upload);
        albumUploadView.setUploadListener((percent, active) -> {
            if (toolbarUploadProgress == null) {
                return;
            }
            toolbarUploadProgress.setIndeterminate(false);
            toolbarUploadProgress.setProgressCompat(percent, true);
            MotionManager.animateVisibility(this, toolbarUploadProgress, active);
        });

        contentHost = findViewById(R.id.content_host);
        bottomOverlay = findViewById(R.id.bottom_overlay);
        overlayContent = findViewById(R.id.overlay_content);
        overlayBlur = findViewById(R.id.overlay_blur);
        wikiView.setOnContentScrollListener(this::requestOverlayBlurUpdate);
        downloadView.setOnContentScrollListener(this::requestOverlayBlurUpdate);
        albumUploadView.setOnContentScrollListener(this::requestOverlayBlurUpdate);

        setupContributions();
        setupBottomOverlay();
        applyNavStyle();
        setupPredictiveBack();

        bottomNav.setItems(Arrays.asList(
                new ExpressiveNavBar.Item(R.id.nav_download,
                        R.drawable.ic_cloud_download, R.drawable.ic_cloud_download_fill,
                        R.string.nav_download),
                new ExpressiveNavBar.Item(R.id.nav_album_upload,
                        R.drawable.ic_album_upload, R.drawable.ic_album_upload_fill,
                        R.string.nav_album_upload),
                new ExpressiveNavBar.Item(R.id.nav_wiki,
                        R.drawable.ic_wiki_search, R.drawable.ic_wiki_search_fill,
                        R.string.nav_wiki),
                new ExpressiveNavBar.Item(R.id.nav_contributions,
                        R.drawable.ic_wiki_book, R.drawable.ic_wiki_book_fill,
                        R.string.nav_contributions)));
        bottomNav.setOnItemSelectedListener(itemId -> {
            HapticsManager.toggle(bottomNav);
            switchDestination(destinationForMenu(itemId));
            return true;
        });

        requestNotificationPermission();
        AuditPollingService.startIfEnabled(this);
        // 低频检查下载目录的自动清理条件（阈值 / 周期）
        DownloadStore.maybeAutoClean(this);

        int startDestination = getIntent() == null
                ? DEST_WIKI
                : getIntent().getIntExtra(EXTRA_DESTINATION, DEST_WIKI);

        if (savedInstanceState != null) {
            wikiView.restoreState(savedInstanceState);
            destination = savedInstanceState.getInt("destination", startDestination);
            currentType = savedInstanceState.getInt("current_type", NetEaseWikiClient.TYPE_VALUES[0]);
        } else {
            destination = startDestination;
            // 无历史时展示初始引导；有历史则恢复上次查询
            wikiView.restoreLastQuery();
        }

        selectBottomNavItem(destination);
        switchDestination(destination);
        // 从推送点击进入时，按载荷决定是否直接切到百科并带词查询
        handlePushIntent(getIntent());

        if (destination == DEST_CONTRIBUTIONS) {
            selectTabForType(currentType);
        }
        // 从「未通过原因」长按进入：切到贡献页并定位到对应卡片
        handleFocusIntent(getIntent());
    }

    /**
     * 处理推送 / 外部 Intent 的落点。
     *
     * <p>规则：带查询词 → 切到「歌曲百科」并直接发起查询；
     * 否则按 {@link #EXTRA_DESTINATION} 切换目的地。重复调用是幂等的。
     */
    private void handlePushIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        String query = intent.getStringExtra(EXTRA_SONG_QUERY);
        if (query != null && !query.trim().isEmpty()) {
            // 消费掉查询词，避免旋转重建时重复触发
            intent.removeExtra(EXTRA_SONG_QUERY);
            selectBottomNavItem(DEST_WIKI);
            switchDestination(DEST_WIKI);
            wikiView.queryFromInput(query.trim());
            return;
        }
        int dest = intent.getIntExtra(EXTRA_DESTINATION, destination);
        if (dest != destination) {
            selectBottomNavItem(dest);
            switchDestination(dest);
        }
    }

    // ------------------------------------------------------------------
    // 从「未通过原因」长按跳转到贡献卡片
    // ------------------------------------------------------------------

    /**
     * 消费定位 Intent（幂等：读取后立即移除，旋转重建不会重复跳转）。
     *
     * <p>定位只做「找到它在第几页」这一件事：先按条目自身的分类逐页扫描拿到全局下标，
     * 再切到该分类并直接加载对应页码，最后由 {@link #renderFetchedPage} 负责滚动与强调。
     * 这样不会把用户的搜索框改写成专辑名，也不会把列表切成检索结果。
     */
    private void handleFocusIntent(@Nullable Intent intent) {
        if (intent == null) {
            return;
        }
        String album = intent.getStringExtra(EXTRA_FOCUS_ALBUM);
        if (album == null || album.trim().isEmpty()) {
            return;
        }
        long time = intent.getLongExtra(EXTRA_FOCUS_TIME, 0L);
        int category = intent.getIntExtra(EXTRA_FOCUS_CATEGORY, RejectArchive.CAT_OTHER);
        intent.removeExtra(EXTRA_FOCUS_ALBUM);
        intent.removeExtra(EXTRA_FOCUS_TIME);
        intent.removeExtra(EXTRA_FOCUS_CATEGORY);
        focusContribution(album.trim(), time, category);
    }

    /** 定位扫描的页大小、单类型页数上限与每波并发页数 */
    private static final int FOCUS_SCAN_PAGE_SIZE = 100;
    private static final int FOCUS_SCAN_MAX_PAGES = 40;
    private static final int FOCUS_SCAN_WAVE = 8;
    /** 扫描结果缓存时长：短时间内连续长按不同条目时不再重复翻页 */
    private static final long FOCUS_SCAN_TTL_MS = 90 * 1000L;
    /** 审核通知必然晚于提交：留 5 分钟服务端时间误差 */
    private static final long FOCUS_NOTICE_SKEW_MS = 5 * 60 * 1000L;

    /**
     * 定位扫描池。
     *
     * <p>单个分类最多近 3000 条（约 28 页），串行翻页要好几秒；这里按波并发拉取，
     * 命中「未采纳 + 早于通知」的确定目标后立即停手，最近的条目通常一波就够。
     */
    private static final ExecutorService FOCUS_SCAN_POOL =
            Executors.newFixedThreadPool(FOCUS_SCAN_WAVE, runnable -> {
                Thread thread = new Thread(runnable, "contrib-focus-scan");
                thread.setDaemon(true);
                return thread;
            });

    /** 定位请求代际：用户连续长按不同条目时只认最后一次 */
    private int focusGeneration;

    /** 待强调的条目稳定键；定位扫描在后台线程写、渲染在主线程读 */
    private volatile String pendingFocusKey;
    private String pendingFocusName;
    private long pendingFocusTime;
    /** 跳转过程中抑制分类 / 状态的自动重载，避免多打一次请求 */
    private boolean suppressReload;

    /** 按类型缓存的扫描结果（名称 + 下标 + 状态 + 提交时间） */
    private final Map<Integer, FocusScan> focusScans = new ConcurrentHashMap<>();

    /** 一条扫描到的贡献记录：只保留定位需要的字段，避免缓存整页对象 */
    private static final class FocusCandidate {
        final String name;
        final int type;
        final int index;
        final int status;
        final long createTime;
        final String key;

        FocusCandidate(String name, int type, int index, int status, long createTime, String key) {
            this.name = name;
            this.type = type;
            this.index = index;
            this.status = status;
            this.createTime = createTime;
            this.key = key;
        }
    }

    private static final class FocusScan {
        final long at;
        final List<FocusCandidate> items;
        /** 已扫描到的页数（被早停时小于 {@link #pages}） */
        final int scanned;
        final int pages;
        final int total;

        FocusScan(long at, List<FocusCandidate> items, int scanned, int pages, int total) {
            this.at = at;
            this.items = items;
            this.scanned = scanned;
            this.pages = pages;
            this.total = total;
        }
    }

    /** 定位结果：分类 + 全局下标 + 稳定键 */
    private static final class FocusTarget {
        final int type;
        final int index;
        final String key;

        FocusTarget(int type, int index, String key) {
            this.type = type;
            this.index = index;
            this.key = key;
        }
    }

    private void focusContribution(String album, long time, int category) {
        if (!SessionManager.isLoggedIn(this)) {
            Feedback.show(findViewById(R.id.bottom_overlay), R.string.contrib_error_not_logged_in);
            return;
        }
        pendingFocusName = album;
        pendingFocusTime = time;
        if (destination != DEST_CONTRIBUTIONS) {
            selectBottomNavItem(DEST_CONTRIBUTIONS);
            // 自定义底栏在「重复选中同一项」时不会回调，这里补一次兜底
            if (destination != DEST_CONTRIBUTIONS) {
                switchDestination(DEST_CONTRIBUTIONS);
            }
        }
        final int focusGen = ++focusGeneration;
        Feedback.show(findViewById(R.id.bottom_overlay), R.string.contrib_focus_locating);
        new Thread(() -> {
            FocusTarget found = null;
            try {
                found = locateContribution(album, time, category, focusGen);
            } catch (WikiException e) {
                Log.w(TAG, "定位贡献失败：" + e.getMessage(), e);
            }
            final FocusTarget result = found;
            runOnUiThread(() -> {
                if (focusGen != focusGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == null) {
                    pendingFocusName = null;
                    pendingFocusTime = 0L;
                    Feedback.show(findViewById(R.id.bottom_overlay),
                            getString(R.string.contrib_focus_not_found, album));
                    return;
                }
                pendingFocusKey = result.key;
                applyFocus(result.type, result.index);
            });
        }, "contrib-focus").start();
    }

    /** 项目类型 → 需要扫描的贡献分类；不确定时退回全部类型。 */
    private static int[] focusTypesFor(int category) {
        switch (category) {
            case RejectArchive.CAT_ALBUM:
                return new int[]{NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT};
            case RejectArchive.CAT_FIX_ALBUM:
                return new int[]{NetEaseWikiClient.TYPE_ALBUM};
            case RejectArchive.CAT_FIX_SONG:
                return new int[]{NetEaseWikiClient.TYPE_SONG};
            case RejectArchive.CAT_FIX_ARTIST:
                return new int[]{NetEaseWikiClient.TYPE_ARTIST};
            case RejectArchive.CAT_LYRIC:
                return new int[]{NetEaseWikiClient.TYPE_LYRIC};
            case RejectArchive.CAT_TRANSLATION:
                return new int[]{NetEaseWikiClient.TYPE_LYRIC_TRANSLATION};
            case RejectArchive.CAT_FIX:
                return new int[]{NetEaseWikiClient.TYPE_ALBUM, NetEaseWikiClient.TYPE_SONG,
                        NetEaseWikiClient.TYPE_ARTIST};
            default:
                return new int[]{NetEaseWikiClient.TYPE_ALBUM_SUPPLEMENT,
                        NetEaseWikiClient.TYPE_ALBUM, NetEaseWikiClient.TYPE_SONG,
                        NetEaseWikiClient.TYPE_ARTIST, NetEaseWikiClient.TYPE_LYRIC,
                        NetEaseWikiClient.TYPE_LYRIC_TRANSLATION};
        }
    }

    /**
     * 分页扫描目标所在的分类与全局下标。
     *
     * <p>必须扫完整个分类再决定：同名条目可能有多次提交（先未采纳、后审核通过），
     * 列表按提交时间倒序，「先遇到哪条就返回」会选中较新的审核通过记录。
     *
     * @return 定位结果；找不到返回 {@code null}
     */
    @Nullable
    private FocusTarget locateContribution(String album, long time, int category, int gen)
            throws WikiException {
        FocusCandidate best = null;
        for (int type : focusTypesFor(category)) {
            if (gen != focusGeneration) {
                return null;
            }
            FocusCandidate candidate;
            try {
                candidate = scanType(type, album, time, gen);
            } catch (WikiException e) {
                Log.w(TAG, "定位扫描失败 type=" + type + "：" + e.getMessage());
                continue;
            }
            if (gen != focusGeneration) {
                return null;
            }
            if (candidate != null && (best == null || better(candidate, best, time))) {
                best = candidate;
            }
        }
        return best == null ? null : new FocusTarget(best.type, best.index, best.key);
    }

    /**
     * 按波扫描一个分类，返回最佳匹配。
     *
     * <p>第一页先拿到总页数，之后每波并发 {@link #FOCUS_SCAN_WAVE} 页。
     * 一旦出现「未采纳且提交早于通知」的确定目标就停止翻更早的页 —— 最近的条目
     * 通常在第一波内命中，不必等整个分类翻完；找不到时才逐波向更早的记录推进。
     */
    @Nullable
    private FocusCandidate scanType(int type, String target, long time, int gen)
            throws WikiException {
        long started = android.os.SystemClock.uptimeMillis();
        List<FocusCandidate> collected;
        int scanned;
        int pages;
        int total;
        FocusScan cached = focusScans.get(type);
        if (cached != null && System.currentTimeMillis() - cached.at < FOCUS_SCAN_TTL_MS) {
            FocusCandidate hit = bestMatch(cached.items, target, time);
            if (decisive(hit, time) || cached.scanned >= cached.pages) {
                return hit;
            }
            // 上一次扫描被早停打断：从断点继续向更早的记录翻
            collected = new ArrayList<>(cached.items);
            scanned = cached.scanned;
            pages = cached.pages;
            total = cached.total;
        } else {
            NetEaseWikiClient.Page first = NetEaseWikiClient.fetchPage(this, type, null, 0,
                    FOCUS_SCAN_PAGE_SIZE, null);
            if (gen != focusGeneration) {
                return null;
            }
            if (first.items.isEmpty()) {
                focusScans.put(type, new FocusScan(System.currentTimeMillis(),
                        Collections.emptyList(), 0, 0, 0));
                return null;
            }
            total = first.total;
            pages = first.pageCount > 0
                    ? first.pageCount
                    : Math.max(1, (total + FOCUS_SCAN_PAGE_SIZE - 1) / FOCUS_SCAN_PAGE_SIZE);
            pages = Math.min(pages, FOCUS_SCAN_MAX_PAGES);
            collected = new ArrayList<>();
            addCandidates(collected, first.items, 0, type);
            scanned = 1;
            FocusCandidate hit = bestMatch(collected, target, time);
            if (decisive(hit, time)) {
                // 首页就命中（最近的条目大多如此）：直接结束，扫描结果留给下次复用
                focusScans.put(type, new FocusScan(System.currentTimeMillis(),
                        collected, scanned, pages, total));
                Log.d(TAG, "定位扫描 type=" + type + " 首页命中 耗时="
                        + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                return hit;
            }
        }

        FocusCandidate best = bestMatch(collected, target, time);
        boolean stopped = decisive(best, time);
        while (scanned < pages && !stopped) {
            int from = scanned;
            int to = Math.min(pages, from + FOCUS_SCAN_WAVE);
            List<Future<NetEaseWikiClient.Page>> futures = new ArrayList<>();
            for (int p = from; p < to; p++) {
                final int offset = p * FOCUS_SCAN_PAGE_SIZE;
                futures.add(FOCUS_SCAN_POOL.submit(() ->
                        NetEaseWikiClient.fetchPage(MainActivity.this, type, null, offset,
                                FOCUS_SCAN_PAGE_SIZE, null)));
            }
            for (int i = 0; i < futures.size(); i++) {
                int pageIndex = from + i;
                scanned++;
                try {
                    List<Contribution> items = futures.get(i).get(15, TimeUnit.SECONDS).items;
                    addCandidates(collected, items, pageIndex * FOCUS_SCAN_PAGE_SIZE, type);
                } catch (Exception e) {
                    Log.w(TAG, "定位扫描第 " + (pageIndex + 1) + " 页失败：" + e.getMessage());
                }
            }
            if (gen != focusGeneration) {
                return null;
            }
            best = bestMatch(collected, target, time);
            if (decisive(best, time)) {
                // 已锁定未采纳且早于通知的记录，更早的页不可能更贴切
                stopped = true;
            }
        }
        focusScans.put(type, new FocusScan(System.currentTimeMillis(),
                collected, scanned, pages, total));
        Log.d(TAG, "定位扫描 type=" + type + " 共" + pages + "页 实扫" + scanned + "页 总数="
                + total + " 早停=" + stopped + " 耗时="
                + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        return best;
    }

    private void addCandidates(List<FocusCandidate> out, List<Contribution> items, int base, int type) {
        for (int i = 0; i < items.size(); i++) {
            Contribution item = items.get(i);
            if (item == null) {
                continue;
            }
            out.add(new FocusCandidate(item.displayName(), type, base + i,
                    item.status, item.createTime, item.stableKey()));
        }
    }

    /** 在已扫描到的记录里挑出与名称匹配、且最合理的一条。 */
    @Nullable
    private static FocusCandidate bestMatch(List<FocusCandidate> candidates, String target, long time) {
        FocusCandidate best = null;
        for (FocusCandidate candidate : candidates) {
            if (!focusNameEquals(candidate.name, target)) {
                continue;
            }
            if (best == null || better(candidate, best, time)) {
                best = candidate;
            }
        }
        return best;
    }

    /** 未采纳且提交不晚于通知：可以确定就是这条，无需继续翻更早的记录。 */
    private static boolean decisive(FocusCandidate candidate, long time) {
        return candidate != null
                && focusStatusRank(candidate.status) == 0
                && (time <= 0 || candidate.createTime <= time + FOCUS_NOTICE_SKEW_MS);
    }

    /**
     * 同名条目里挑最合理的一条。
     *
     * <p>归档里的通知全部是「未通过」，因此未采纳记录优先；其次提交时间必须早于通知
     * （通知不可能早于提交），在满足条件的记录里取离通知最近的一条。
     */
    private static boolean better(FocusCandidate candidate, FocusCandidate best, long time) {
        int rank = focusStatusRank(candidate.status);
        int bestRank = focusStatusRank(best.status);
        if (rank != bestRank) {
            return rank < bestRank;
        }
        boolean before = time <= 0 || candidate.createTime <= time + FOCUS_NOTICE_SKEW_MS;
        boolean bestBefore = time <= 0 || best.createTime <= time + FOCUS_NOTICE_SKEW_MS;
        if (before != bestBefore) {
            return before;
        }
        if (before) {
            return candidate.createTime > best.createTime;
        }
        return Math.abs(candidate.createTime - time) < Math.abs(best.createTime - time);
    }

    private static int focusStatusRank(int status) {
        switch (status) {
            case Contribution.STATUS_REJECTED:
                return 0;
            case Contribution.STATUS_PARTIAL:
                return 1;
            case Contribution.STATUS_AUDITING:
                return 2;
            case Contribution.STATUS_PENDING:
                return 3;
            case Contribution.STATUS_APPROVED:
                return 4;
            default:
                return 5;
        }
    }

    private static boolean focusNameEquals(String candidate, String target) {
        return focusNameKey(candidate).equals(focusNameKey(target));
    }

    /** 名称归一化：忽略空白、零宽字符与全角 / 半角差异 */
    private static String focusNameKey(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace('\u00a0', ' ').replace('　', ' ')
                .replaceAll("[\\u200B-\\u200F\\uFEFF]", "")
                .replace('（', '(').replace('）', ')')
                .replace('：', ':').replace('／', '/');
        return v.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 切到目标分类并直接加载它所在的那一页。 */
    private void applyFocus(int type, int index) {
        final int page = Math.max(1, index / NetEaseWikiClient.PAGE_SIZE + 1);
        // 搜索条件会把目标过滤掉，这里先清空（同步清空输入框，避免界面与实际条件不一致）
        searchKeyword = "";
        searchDateFrom = 0;
        searchDateTo = 0;
        if (etSearchKeyword != null) {
            etSearchKeyword.setText("");
        }
        if (etSearchKeywordCompact != null) {
            etSearchKeywordCompact.setText("");
        }
        updateSearchDateLabel();
        searching = false;
        scanCache = null;
        pageCache.clear();
        suppressReload = true;
        try {
            if (currentStatus != null && chipStatus != null && chipStatus.getChildCount() > 0) {
                chipStatus.check(chipStatus.getChildAt(0).getId());
            }
            currentStatus = null;
            if (currentType != type) {
                selectTabForType(type);
                currentType = type;
            }
        } finally {
            suppressReload = false;
        }
        Log.d(TAG, "定位跳转 type=" + type + " 全局下标=" + index + " 页码=" + page);
        loadPage(page);
    }

    /**
     * 目标页渲染完成后：滚动到卡片并短暂强调。
     *
     * <p>新一页是先提交给 DiffUtil、再由后台线程把结果交回主线程的，
     * 渲染回调发生时 {@code getCurrentList()} 可能还停留在上一页（实测 60 条 vs 20 条），
     * 因此这里按帧重试几次，直到目标条目真正进入列表。
     */
    private void resolvePendingFocusCard() {
        resolvePendingFocusCard(0);
    }

    private void resolvePendingFocusCard(final int attempt) {
        final String key = pendingFocusKey;
        if (key == null || adapter == null) {
            return;
        }
        int position = -1;
        List<Contribution> current = adapter.getCurrentList();
        for (int i = 0; i < current.size(); i++) {
            Contribution item = current.get(i);
            if (item != null && key.equals(item.stableKey())) {
                position = i;
                break;
            }
        }
        if (position < 0 && attempt < 4) {
            recyclerView.postDelayed(() -> resolvePendingFocusCard(attempt + 1), 50L);
            return;
        }
        pendingFocusKey = null;
        final String name = pendingFocusName;
        pendingFocusName = null;
        pendingFocusTime = 0L;
        Log.d(TAG, "定位命中 position=" + position + " 当前页条数=" + current.size());
        if (position < 0) {
            Feedback.show(findViewById(R.id.bottom_overlay),
                    getString(R.string.contrib_focus_not_found, name == null ? "" : name));
            return;
        }
        // 定位已完成，收起「正在定位…」，把视线让给被强调的卡片
        Feedback.dismiss();
        focusCardAt(position);
    }

    /**
     * 定位并短暂强调目标卡片。
     *
     * <p>不在视口内时先把目标滚到屏幕三分之一处（原生列表的「跳到条目」语义），
     * 再用主色描边 + 轻微放大做一次 Expressive 强调，1.2 秒后恢复原状。
     */
    private void focusCardAt(int position) {
        focusCardAt(position, 0);
    }

    private void focusCardAt(final int position, final int attempt) {
        if (recyclerView == null || adapter == null || position < 0) {
            return;
        }
        recyclerView.post(() -> {
            View item = itemViewAt(position);
            if (item == null) {
                Log.d(TAG, "定位卡片未布局 position=" + position + " 第" + attempt + "次");
                if (attempt < 3) {
                    recyclerView.postDelayed(() -> focusCardAt(position, attempt + 1), 60L);
                }
                return;
            }
            scrollOuterTo(item);
            recyclerView.postDelayed(() -> pulseCard(position, 0), 60L);
        });
    }

    @Nullable
    private View itemViewAt(int position) {
        if (recyclerView == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView;
    }

    /**
     * 把目标卡片滚到可视区上三分之一处。
     *
     * <p>贡献列表本身高度为 {@code wrap_content} 且禁用了嵌套滚动（由外层
     * {@code contrib_scroll} 统一滚动），因此 {@code scrollToPositionWithOffset}
     * 只会改变列表内部的布局偏移，界面纹丝不动 —— 必须直接滚动外层容器。
     */
    private void scrollOuterTo(View item) {
        if (contribScroll == null || item == null) {
            return;
        }
        int offset = 0;
        View v = item;
        while (v != null && v != contribScroll) {
            offset += v.getTop() - v.getScrollY();
            android.view.ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        final int target = Math.max(0, offset - Math.max(0, contribScroll.getHeight() / 3));
        Log.d(TAG, "滚动到卡片 offset=" + offset + " target=" + target
                + " 容器高=" + contribScroll.getHeight() + " 高=" + item.getHeight());
        contribScroll.smoothScrollTo(0, target);
    }

    private void pulseCard(int position, int attempt) {
        if (recyclerView == null || adapter == null) {
            return;
        }
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (holder == null) {
            // 布局还没产出这一项：再等一帧，最多重试两次
            if (attempt < 2) {
                recyclerView.postDelayed(() -> pulseCard(position, attempt + 1), 90L);
            }
            return;
        }
        View root = holder.itemView.findViewById(R.id.card_root);
        if (!(root instanceof MaterialCardView)) {
            return;
        }
        MaterialCardView card = (MaterialCardView) root;
        // 记录稳定键：1.2 秒内列表若换了页，不能把新条目当成目标去重绑
        final String key;
        try {
            key = adapter.getCurrentList().get(position).stableKey();
        } catch (Exception e) {
            return;
        }
        final int strokeColor = MaterialColors.getColor(card,
                androidx.appcompat.R.attr.colorPrimary);
        int stroke = getResources().getDimensionPixelSize(R.dimen.stroke_emphasis);
        card.setStrokeWidth(stroke);
        card.setStrokeColor(strokeColor);
        HapticsManager.confirm(card);
        card.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140L)
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220L).start())
                .start();
        card.postDelayed(() -> {
            // 强调消失走颜色缓动：主色 → 全透明，描边宽度保持到动画结束再归零，
            // 避免「啪」地一下切回默认样式
            ValueAnimator fade = ValueAnimator.ofObject(new ArgbEvaluator(), strokeColor,
                    android.graphics.Color.argb(0,
                            android.graphics.Color.red(strokeColor),
                            android.graphics.Color.green(strokeColor),
                            android.graphics.Color.blue(strokeColor)));
            fade.setDuration(460L);
            fade.setInterpolator(MotionManager.standard(this));
            fade.addUpdateListener(a -> card.setStrokeColor((int) a.getAnimatedValue()));
            fade.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        if (position < adapter.getItemCount()
                                && key.equals(adapter.getCurrentList().get(position).stableKey())) {
                            // 重新绑定一次，恢复未读描边等原始样式
                            adapter.notifyItemChanged(position);
                        } else {
                            card.setStrokeWidth(0);
                        }
                    } catch (Exception e) {
                        // 正在计算布局时跳过，卡片下轮滚动会自然重绑
                        card.setStrokeWidth(0);
                    }
                }
            });
            fade.start();
        }, 1200L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
        handlePushIntent(intent);
        // 应用已在前台时点击通知 / 长按跳转不会走 onResume，这里统一消费
        handleFocusIntent(intent);
        refreshUnreadState();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        wikiView.saveState(outState);
        outState.putInt("destination", destination);
        outState.putInt("current_type", currentType);
    }

    @Override
    protected void onDestroy() {
        generation++;
        statsGeneration++;
        cancelInflight();
        WikiPrefs.get(this).unregisterOnSharedPreferenceChangeListener(displayPrefListener);
        wikiView.dispose();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 顶栏菜单：设置入口
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        // 顶栏图标统一按主题着色：矢量图标自身是白色，浅色主题下不染色会「白底白图标」
        ColorStateList iconTint = ColorStateList.valueOf(MaterialColors.getColor(toolbar,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        MenuItem settings = menu.findItem(R.id.action_settings);
        if (settings != null) {
            settings.setIconTintList(iconTint);
        }
        // 应用内消息聚合入口：轻量图标 + 未读角标，统一展示未读审核更新
        MenuItem messages = menu.add(Menu.NONE, R.id.action_messages, 4,
                R.string.msg_title);
        messages.setIcon(R.drawable.ic_notifications);
        messages.setIconTintList(iconTint);
        messages.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
        messagesItem = messages;
        // 专辑上传页在顶栏提供「提交审核」入口：长表单无需滚到底部，
        // 也不与悬浮底栏争夺底部空间。
        MenuItem submit = menu.add(Menu.NONE, R.id.action_submit_album, 50,
                R.string.au_action_submit);
        submit.setIcon(R.drawable.ic_check_circle);
        submit.setIconTintList(iconTint);
        submit.setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        submit.setVisible(destination == DEST_ALBUM_UPLOAD);
        submitAlbumItem = submit;
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (submitAlbumItem != null) {
            submitAlbumItem.setVisible(destination == DEST_ALBUM_UPLOAD);
        }
        updateMessageBadge();
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_messages) {
            showAuditMessages();
            return true;
        }
        if (item.getItemId() == R.id.action_submit_album) {
            if (albumUploadView != null) {
                albumUploadView.submitFromToolbar();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // 应用内消息：未读审核聚合
    // ------------------------------------------------------------------

    /** 刷新贡献列表「新」标记与顶栏角标。 */
    private void refreshUnreadState() {
        Set<String> keys = AuditInbox.unreadKeys(this);
        if (adapter != null) {
            adapter.setUnreadKeys(keys);
        }
        updateMessageBadge(keys.size());
    }

    private void updateMessageBadge() {
        updateMessageBadge(AuditInbox.unreadCount(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void updateMessageBadge(int count) {
        if (messagesItem == null || toolbar == null) {
            return;
        }
        View anchor = toolbar.findViewById(R.id.action_messages);
        if (anchor == null) {
            // 菜单项的 ActionView 由 Toolbar 在 prepare 之后才创建，
            // 首次刷新时可能还拿不到锚点，延后一帧重试，避免角标一直挂不上
            if (!badgeAttachPending && badgeAttachRetries < MAX_BADGE_ATTACH_RETRIES) {
                badgeAttachPending = true;
                badgeAttachRetries++;
                toolbar.post(() -> {
                    badgeAttachPending = false;
                    updateMessageBadge();
                });
            }
            return;
        }
        if (messageBadge == null) {
            messageBadge = BadgeDrawable.create(this);
        }
        if (anchor != badgeAnchor) {
            // 菜单重建后必须换锚点：同一个 BadgeDrawable 只能挂在一个 ActionView 上
            if (badgeAnchor != null) {
                BadgeUtils.detachBadgeDrawable(messageBadge, badgeAnchor);
            }
            BadgeUtils.attachBadgeDrawable(messageBadge, toolbar, R.id.action_messages);
            badgeAnchor = anchor;
            badgeAttachRetries = 0;
        }
        messageBadge.setNumber(count);
        messageBadge.setVisible(count > 0);
    }

    /** 顶栏消息入口：聚合展示全部未读审核更新。 */
    private void showAuditMessages() {
        final List<AuditInbox.Entry> entries = AuditInbox.entries(this);
        if (entries.isEmpty()) {
            // 空态也走同一个底部抽屉：此前用 AlertDialog，与有未读时的抽屉形态完全两种观感
            SheetList.message(this, getString(R.string.msg_title), getString(R.string.msg_empty));
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            AuditInbox.Entry entry = entries.get(i);
            labels[i] = entry.name + " · " + Contribution.statusName(entry.status);
        }
        // 消息列表是浏览型列表，用底部抽屉展示；「全部已读」作为抽屉底部动作
        SheetList.actions(this, getString(R.string.msg_title), labels,
                getString(R.string.msg_mark_all_read),
                () -> {
                    AuditInbox.markAllRead(this);
                    refreshUnreadState();
                },
                which -> {
                    AuditInbox.Entry entry = entries.get(which);
                    AuditInbox.markRead(this, entry.key);
                    refreshUnreadState();
                    selectBottomNavItem(DEST_CONTRIBUTIONS);
                    switchDestination(DEST_CONTRIBUTIONS);
                });
    }

    // ------------------------------------------------------------------
    // 悬浮覆盖式底栏（底栏不占内容高度，且不遮挡内容）
    // ------------------------------------------------------------------

    /**
     * 底栏为悬浮覆盖层，底部留白由覆盖层实测高度承担。
     *
     * <p>若仍由 {@link BaseActivity} 叠加 {@code systemBars / ime} 底部内边距，覆盖层会被
     * 再次顶起并撑高（点击搜索框唤起输入法时表现为「底栏突发放大并全屏遮挡」），
     * 因此这里关闭基类的底部补偿，改由覆盖层自身承接。
     */
    @Override
    protected boolean handlesBottomInsets() {
        return true;
    }

    private void setupBottomOverlay() {
        if (overlayBlur != null && contentHost != null) {
            // 采样整个内容宿主，而不是某一个目的地页面：
            //  1. 切换标签时两页在交叉淡入淡出，采样宿主才能得到「真正压在底栏下方」的合成结果；
            //     若采样正在淡入的新页（alpha 从 0 开始），过渡期间会采到近乎透明的内容，
            //     表现为「模糊短暂消失再恢复」。
            //  2. 目的地切换不再需要更换模糊源，避免一次性的重采样空窗。
            overlayBlur.setSourceView(contentHost);
        }
        if (bottomOverlay != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomOverlay, (view, windowInsets) -> {
                // 系统栏底部高度交给底栏外壳自己吸收：
                //   悬浮态 → 变成外壳的下外边距，胶囊浮在手势条之上；
                //   贴底态 → 变成外壳的下内边距，背景一直铺到屏幕底边（不再漏出一条内容）。
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                Insets gestures = windowInsets.getInsets(
                        WindowInsetsCompat.Type.mandatorySystemGestures());
                int safeBottom = Math.max(bars.bottom, gestures.bottom);
                if (bars.bottom != navBarInset || safeBottom != navBottomSafeInset) {
                    navBarInset = bars.bottom;
                    navBottomSafeInset = safeBottom;
                    applyNavStyle();
                }
                // 输入法用位移避让：padding 会被重新测量成「更高」，正是底栏放大的根因。
                int ime = imeBottomInset(windowInsets);
                if (overlayBottomInset != ime) {
                    overlayBottomInset = ime;
                    applyContentBottomInset();
                }
                view.setTranslationY(-ime);
                requestOverlayBlurUpdate();
                return windowInsets;
            });
            // 输入法动画期间 insets 默认在动画结束后才下发一次，底栏会「瞬移」。
            // 注册动画回调后按帧拿到插值中的 inset，位移与键盘同步，观感才是连续的。
            ViewCompat.setWindowInsetsAnimationCallback(bottomOverlay,
                    new WindowInsetsAnimationCompat.Callback(
                            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                        @NonNull
                        @Override
                        public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                                                             @NonNull List<WindowInsetsAnimationCompat> running) {
                            bottomOverlay.setTranslationY(-imeBottomInset(insets));
                            requestOverlayBlurUpdate();
                            return insets;
                        }

                        @Override
                        public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                            // 动画可能被打断在半途，结束（含取消）后按真实 insets 再对齐一次
                            bottomOverlay.post(MainActivity.this::syncOverlayImeOffset);
                        }
                    });
            ViewCompat.requestApplyInsets(bottomOverlay);
        }
        if (overlayContent != null) {
            overlayContent.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                      oldLeft, oldTop, oldRight, oldBottom) ->
                    syncOverlayBounds());
            overlayContent.post(this::syncOverlayBounds);
        }
    }

    /** 同步玻璃层与覆盖层的实际边界，避免 wrap_content + match_parent 组合把整块量成全屏。 */
    private void syncOverlayBounds() {
        if (overlayContent == null) {
            return;
        }
        int contentHeight = overlayContent.getHeight();
        if (contentHeight > 0 && contentHeight != overlayHeight) {
            overlayHeight = contentHeight;
            applyContentBottomInset();
        }
        if (overlayBlur == null) {
            return;
        }
        // 玻璃层与底栏外壳共用同一个下外边距，因此高度要扣掉这段间距，
        // 否则模糊层会从胶囊上方多出一条半透明带（悬浮态的底部间距越大越明显）。
        int bottomGap = 0;
        if (navShell != null && navShell.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            bottomGap = ((ViewGroup.MarginLayoutParams) navShell.getLayoutParams()).bottomMargin;
        }
        int blurHeight = Math.max(0, contentHeight - bottomGap);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayBlur.getLayoutParams();
        boolean changed = false;
        if (lp.gravity != Gravity.BOTTOM) {
            lp.gravity = Gravity.BOTTOM;
            changed = true;
        }
        if (lp.height != blurHeight) {
            lp.height = blurHeight;
            changed = true;
        }
        if (changed) {
            overlayBlur.setLayoutParams(lp);
        }
    }

    /**
     * 覆盖层需要为输入法让出的高度。
     *
     * <p>输入法只在 Android 11（API 30）及以上用位移避让：更低版本的窗口本身仍会随输入法
     * 自动缩小，若再位移一次，底栏会被推高「两个键盘」的高度，表现为突然放大或跳到屏幕中部。
     *
     * <p>系统栏底部不再参与位移：悬浮态由底栏外壳的下外边距让开手势条，
     * 贴底态由外壳的下内边距把背景铺到屏幕底边。
     */
    private int imeBottomInset(WindowInsetsCompat windowInsets) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
    }

    /**
     * 按当前真实 insets 重新对齐覆盖层的输入法位移。
     *
     * <p>输入法的进出是逐帧动画：期间若窗口被切换（打开其它 Activity、切后台、
     * 弹窗夺取焦点），动画会停在中间某一帧，之后又可能等不到最终那次 insets 下发，
     * 覆盖层就永久停在「被键盘顶起」的位置——表现为底栏固定卡在页面中部。
     * 窗口重新获得焦点与动画结束时各对齐一次，可自行纠正这种半途状态。
     */
    private void syncOverlayImeOffset() {
        if (bottomOverlay == null) {
            return;
        }
        WindowInsetsCompat root = ViewCompat.getRootWindowInsets(bottomOverlay);
        int ime = root == null ? 0 : imeBottomInset(root);
        bottomOverlay.setTranslationY(-ime);
        if (overlayBottomInset != ime) {
            overlayBottomInset = ime;
            applyContentBottomInset();
        }
        requestOverlayBlurUpdate();
    }

    /** 把覆盖层的输入法位移与底部留白一起归零（离开页面时的兜底归位）。 */
    private void resetOverlayImeOffset() {
        if (bottomOverlay != null && bottomOverlay.getTranslationY() != 0f) {
            bottomOverlay.setTranslationY(0f);
            requestOverlayBlurUpdate();
        }
        if (overlayBottomInset != 0) {
            overlayBottomInset = 0;
            applyContentBottomInset();
        }
    }

    /**
     * 把覆盖层高度补成滚动内容的底部留白。
     *
     * <p>两个目的地页面的滚动容器都关闭了 {@code clipToPadding}，因此留白只影响「能滚多远」，
     * 内容本身依然从半透明底栏下穿过并被模糊，视觉上不会出现被硬切的黑边。
     */
    private void applyContentBottomInset() {
        int imeInset = overlayBottomInset;
        if (contribScroll != null) {
            // 贡献页：整块覆盖层（页面选择器 + 底栏）都可能压住内容，留白取实测总高
            contribScroll.setPadding(contribScroll.getPaddingLeft(), contribScroll.getPaddingTop(),
                    contribScroll.getPaddingRight(), overlayHeight + imeInset);
        }
        if (wikiView != null) {
            // 百科页：页面选择器不参与（它只在贡献页出现），只让出底栏本身的高度。
            // 否则切页时页面选择器收起会让百科页的底部留白跟着变，产生一次滚动位移。
            wikiView.setBottomInset(navOnlyInset() + imeInset);
        }
        if (downloadView != null) {
            downloadView.setBottomInset(navOnlyInset() + imeInset);
        }
        if (albumUploadView != null) {
            albumUploadView.setBottomInset(navOnlyInset() + imeInset);
        }
    }

    /** 底栏（不含页面选择器）占据的高度：底栏高度 + 其下外边距 + 覆盖层内边距 */
    private int navOnlyInset() {
        if (navShell == null || navShell.getHeight() <= 0) {
            return overlayHeight;
        }
        int bottomMargin = 0;
        ViewGroup.LayoutParams params = navShell.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            bottomMargin = ((ViewGroup.MarginLayoutParams) params).bottomMargin;
        }
        int extra = overlayContent == null ? 0 : overlayContent.getPaddingBottom();
        return navShell.getHeight() + bottomMargin + extra;
    }

    /**
     * 页面选择器显隐：仅在「我的贡献」且列表有内容时展开，切换时播放上滑 / 下滑动画。
     *
     * <p>退场时形状（页面选择器与底栏合并为一块）要等动画结束后再恢复为独立胶囊，
     * 否则动画途中底栏上圆角先出现，会看到一条「断层」并伴随玻璃层高度变化。
     */
    private void setPagerVisible(boolean visible) {
        if (pagerBar == null || visible == pagerShown) {
            return;
        }
        pagerShown = visible;
        long duration = MotionManager.controlDuration(this);
        if (visible) {
            // 动画期间先撤投影：覆盖层高度还在随选择器变化，提前投影会得到一块错位的阴影
            applyOverlayElevation(0);
            // 展开：上圆角从胶囊半径补间到 0，与滑入同时开始，避免直角突然出现
            animateNavCornerTo(0, duration);
            MotionManager.slideInFromBottom(this, pagerBar, () -> {
                requestOverlayBlurUpdate();
                applyNavShadow();
            });
        } else {
            // 投影立即交回底栏自身：覆盖层的投影必须撤掉（其高度仍包含正在滑出的
            // 选择器，阴影会滞留在原位），但底栏此刻已在最终位置，若等到动画结束
            // 再给它投影，就会看到「阴影先消失、再出现」的闪动。
            applyNavShadow();
            // 收起：上圆角与滑出同时补间回胶囊半径，选择器滑走时圆角已经长好，
            // 不会在动画结束的那一帧出现「直角突变为圆角」
            animateNavCornerTo(navRadiusPx(), duration);
            MotionManager.slideOutToBottom(this, pagerBar, () -> {
                if (!pagerShown) {
                    requestOverlayBlurUpdate();
                    applyNavShadow();
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 目的地切换
    // ------------------------------------------------------------------

    /** 目的地 → 底栏菜单 id（与 {@link #destinationForMenu(int)} 互逆） */
    private int menuIdForDestination(int dest) {
        switch (dest) {
            case DEST_DOWNLOAD:
                return R.id.nav_download;
            case DEST_ALBUM_UPLOAD:
                return R.id.nav_album_upload;
            case DEST_CONTRIBUTIONS:
                return R.id.nav_contributions;
            default:
                return R.id.nav_wiki;
        }
    }

    private int destinationForMenu(int itemId) {
        if (itemId == R.id.nav_download) {
            return DEST_DOWNLOAD;
        }
        if (itemId == R.id.nav_album_upload) {
            return DEST_ALBUM_UPLOAD;
        }
        if (itemId == R.id.nav_contributions) {
            return DEST_CONTRIBUTIONS;
        }
        return DEST_WIKI;
    }

    private View pageFor(int dest) {
        switch (dest) {
            case DEST_DOWNLOAD:
                return pageDownload;
            case DEST_ALBUM_UPLOAD:
                return pageAlbumUpload;
            case DEST_CONTRIBUTIONS:
                return pageContributions;
            default:
                return pageWiki;
        }
    }

    /** 隐藏除指定页面外的所有目的地页（保留指定页当前的可见性 / 透明度） */
    private void hidePagesExcept(View... keep) {
        View[] pages = {pageWiki, pageContributions, pageDownload, pageAlbumUpload};
        for (View page : pages) {
            if (page == null) {
                continue;
            }
            boolean kept = false;
            for (View k : keep) {
                if (k == page) {
                    kept = true;
                    break;
                }
            }
            if (!kept) {
                page.setVisibility(View.INVISIBLE);
            }
        }
    }

    private int titleFor(int dest) {
        switch (dest) {
            case DEST_DOWNLOAD:
                return R.string.nav_download;
            case DEST_ALBUM_UPLOAD:
                return R.string.nav_album_upload;
            case DEST_CONTRIBUTIONS:
                return R.string.nav_contributions;
            default:
                return R.string.nav_wiki;
        }
    }

    private void selectBottomNavItem(int dest) {
        bottomNav.setSelectedItemId(menuIdForDestination(dest));
    }

    private void switchDestination(int dest) {
        boolean wiki = dest == DEST_WIKI;
        boolean contributions = dest == DEST_CONTRIBUTIONS;
        View incoming = pageFor(dest);
        View outgoing = pageFor(destination);
        if (incoming == null || outgoing == null) {
            destination = dest;
            return;
        }
        // 只比较目的地本身：首次进入 / 主题重建后 restored destination 与目标页相同，
        // 此时 outgoing == incoming，再走交叉淡入淡出会把这一页自己淡到 INVISIBLE，
        // 露出 XML 里默认可见的歌曲百科页（表现为「标题是上传页、内容是百科页」）。
        boolean switched = destination != dest;
        destination = dest;
        invalidateOptionsMenu();
        // 切页时先收起输入法：否则新页面首帧仍在 IME inset 状态，底栏会先抬升再回落
        hideKeyboard();
        // 离开贡献页时收尾未完成的翻页过渡，避免回来时列表仍停在压暗态
        if (!contributions && listDimmed) {
            listDimmed = false;
            MotionManager.undimList(this, recyclerView);
        }
        if (!wiki && overlayBlur != null) {
            overlayBlur.setDrawOffsetY(0f);
        }

        int titleRes = titleFor(dest);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(titleRes));
        }
        toolbar.setTitle(getString(titleRes));

        // 除入场页外一律隐藏：目的地页的初始可见性来自 XML，重建后不会自动复位，
        // 不显式收口会让两页同时可见（内容互相透出）。
        if (switched) {
            hidePagesExcept(outgoing, incoming);
            MotionManager.clearBlur(outgoing);
            MotionManager.clearBlur(incoming);
            incoming.setTranslationX(0f);
            outgoing.setTranslationX(0f);
            if (outgoing.getVisibility() == View.VISIBLE) {
                // 层级切换过渡：离场内容淡出（可选离场模糊），入场内容淡入上浮
                MotionManager.crossFade(this, outgoing, incoming, true);
            } else {
                outgoing.setVisibility(View.INVISIBLE);
                incoming.setVisibility(View.VISIBLE);
                incoming.setAlpha(1f);
                incoming.setTranslationY(0f);
            }
            if (overlayBlur != null) {
                // 模糊源固定为 contentHost（见 setupBottomOverlay），这里只触发重采样。
                // 过渡期间两页交叉淡入淡出，必须按帧跟随采样，否则新页面显现前
                // 模糊会一直停留在旧内容上（表现为「模糊滞后出现」）。
                overlayBlur.markDirty();
                overlayBlur.followFor(MotionManager.destinationDuration(this) + 120L);
            }
        } else {
            // 首次进入 / 重建恢复：不播放过渡，直接把目标页摆到前台并复位残留的透明度
            hidePagesExcept(incoming);
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(1f);
            incoming.setTranslationY(0f);
        }

        // 页面选择器只在「我的贡献」且有内容时展开，进出该标签页时播放上滑 / 下滑动画
        setPagerVisible(contributions && currentStateTag == STATE_CONTENT);

        if (contributions && currentStateTag == STATE_IDLE) {
            reload();
        }
        // 从声云下载页点「传入专辑上传」后，切换过来时立即消费交接资源
        if (dest == DEST_ALBUM_UPLOAD && albumUploadView != null) {
            albumUploadView.consumeTransferStash();
        }
        if (backCallback != null) {
            backCallback.setEnabled(contributions);
        }
    }

    // ------------------------------------------------------------------
    // 底部导航栏样式（第二阶段：悬浮胶囊 / 贴底通栏）
    // ------------------------------------------------------------------

    /**
     * 按用户选择重排导航栏外壳。幂等，可在 onResume 里重复调用：
     * 用户从「设置-外观」返回时即可看到新样式，且无需整栈 recreate。
     *
     * <p>同时把同样的边距 / 圆角下发给玻璃模糊层，使「页面选择器 + 底栏」被同一块玻璃
     * 材质完整包住 —— 页面选择器因此恒紧贴底栏上边缘，且两者材质视觉完全统一。
     */
    private void applyNavStyle() {
        if (navShell == null) {
            return;
        }
        boolean floating = AppearanceManager.isFloatingNav(this);
        int horizontal = floating ? px(NAV_MARGIN_H_DP) : 0;
        int bottomMargin = floating ? navBarInset + px(NAV_MARGIN_BOTTOM_DP) : 0;
        // 贴底态：底栏自身在底部多留一段手势安全区，让背景一直铺到屏幕最底边。
        // 这段高度必须加在底栏（而不是外层 CardView 的 padding）上：
        // MaterialCardView 的 padding 不参与自身 wrap_content 的高度计算，
        // 直接给卡片设 padding 会出现「内容不下移、卡片也不变高」。
        int safeBottom = floating ? 0 : navBottomSafeInset;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) navShell.getLayoutParams();
        lp.setMarginStart(horizontal);
        lp.setMarginEnd(horizontal);
        lp.bottomMargin = bottomMargin;
        navShell.setLayoutParams(lp);
        if (bottomNav != null) {
            ViewGroup.LayoutParams navLp = bottomNav.getLayoutParams();
            int height = getResources().getDimensionPixelSize(R.dimen.nav_item_height) + safeBottom;
            if (navLp.height != height) {
                navLp.height = height;
                bottomNav.setLayoutParams(navLp);
            }
            bottomNav.setPadding(0, 0, 0, safeBottom);
        }

        int radius = floating ? px(NAV_RADIUS_DP) : 0;
        navShell.setRadius(radius);

        int glass = AppearanceManager.navGlassStyle(this);
        boolean glassOn = glass != AppearanceManager.GLASS_NONE;
        applyPagerSurface(glassOn, radius, floating);
        // 页面选择器与底栏合并为同一块表面（合并态由 pagerShown 决定），
        // 必须在 setRadius 之后调用：setRadius 会把形状重置为四角同半径。
        applyPagerMergedShape();
        // 开启玻璃时底栏自身留空：材质与配色统一由 BackdropBlurView 承载，
        // 底栏只保留图标与文字，从而让底层内容真正「透」出来。
        navShell.setCardBackgroundColor(glassOn
                ? Color.TRANSPARENT
                : MaterialColors.getColor(navShell, floating
                        ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                        : com.google.android.material.R.attr.colorSurfaceContainer));
        // 投影在「底栏自身」与「覆盖层整体」之间切换（合并态由选择器承担）
        applyNavShadow();

        if (overlayBlur != null) {
            FrameLayout.LayoutParams blurLp =
                    (FrameLayout.LayoutParams) overlayBlur.getLayoutParams();
            blurLp.setMarginStart(horizontal);
            blurLp.setMarginEnd(horizontal);
            blurLp.bottomMargin = bottomMargin;
            blurLp.gravity = Gravity.BOTTOM;
            overlayBlur.setLayoutParams(blurLp);
            overlayBlur.setCornerRadiusPx(radius);
            overlayBlur.setGlassStyle(glass, glassTint(glass), glassRim(glass));
            syncOverlayBounds();
            overlayBlur.markDirty();
        }

        // 外壳高度会随「贴底补底」变化，下一帧把新的高度同步给各页的滚动留白
        navShell.post(() -> {
            applyContentBottomInset();
            syncOverlayBounds();
        });
    }

    /**
     * 无模糊态下，页面选择器也需要一层明确的 MD3 表面背景，避免文字直接压在内容上。
     *
     * <p>关键约束（无缝衔接）：
     * <ul>
     *   <li>页面选择器与底栏使用<b>同一外边距</b>，左右边缘严格对齐；</li>
     *   <li>页面选择器只保留上圆角、底边直角，底栏在合并态只保留底圆角 ——
     *       两块表面拼成一条连续的面，不会出现「两个圆角胶囊叠在一起」的断层；</li>
     *   <li>填充色与底栏完全一致且不透明，避免接缝处出现一条色差。</li>
     * </ul>
     */
    private void applyPagerSurface(boolean glassOn, int radius, boolean floating) {
        if (pagerBar == null) {
            return;
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) pagerBar.getLayoutParams();
        int horizontal = floating ? px(NAV_MARGIN_H_DP) : 0;
        if (lp.getMarginStart() != horizontal || lp.getMarginEnd() != horizontal) {
            lp.setMarginStart(horizontal);
            lp.setMarginEnd(horizontal);
            pagerBar.setLayoutParams(lp);
        }
        if (glassOn) {
            // 玻璃模式：材质由共用的 BackdropBlurView 承载，叠加第二层半透明会发灰
            pagerBar.setBackground(null);
            return;
        }
        int fill = MaterialColors.getColor(pagerBar, floating
                ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                : com.google.android.material.R.attr.colorSurfaceContainer);
        MaterialShapeDrawable bg = new MaterialShapeDrawable(
                ShapeAppearanceModel.builder()
                        .setTopLeftCornerSize(radius)
                        .setTopRightCornerSize(radius)
                        .setBottomLeftCornerSize(0f)
                        .setBottomRightCornerSize(0f)
                        .build());
        bg.setFillColor(ColorStateList.valueOf(fill));
        pagerBar.setBackground(bg);
    }

    /**
     * 底栏形状：页面选择器展开时与之合并（上直角 + 下圆角），收起时恢复完整胶囊。
     *
     * <p>合并态同时把海拔归零：底栏自身的投影会正好落在与选择器的接缝上，
     * 看起来像一条分割线，破坏「同一块表面」的连续感。
     *
     * <p>这里直接落到终态，用于初始化 / onResume 等无需过渡的场景；
     * 展开与收起的动画见 {@link #animateNavCornerTo(int, long)}。
     */
    private void applyPagerMergedShape() {
        if (navShell == null) {
            return;
        }
        if (navCornerAnimator != null) {
            navCornerAnimator.cancel();
        }
        applyNavCorner(pagerShown ? 0 : navRadiusPx());
    }

    /** 底栏圆角半径（px）：悬浮胶囊 20dp，贴底通栏 0 */
    private int navRadiusPx() {
        return AppearanceManager.isFloatingNav(this) ? px(NAV_RADIUS_DP) : 0;
    }

    /** 按「上圆角半径」设置底栏形状；下圆角恒为胶囊半径 */
    private void applyNavCorner(int topCornerPx) {
        if (navShell == null) {
            return;
        }
        navTopCornerPx = topCornerPx;
        int radius = navRadiusPx();
        ShapeAppearanceModel shape = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(topCornerPx)
                .setTopRightCornerSize(topCornerPx)
                .setBottomLeftCornerSize(radius)
                .setBottomRightCornerSize(radius)
                .build();
        navShell.setShapeAppearanceModel(shape);
    }

    /**
     * 投影归属：未合并时由底栏外壳投影；页面选择器展开后由覆盖层整体投影。
     *
     * <p>合并态不能让底栏自己投影：阴影会正好画在与选择器的接缝上，看起来像一条分割线。
     * 反过来也不能在选择器滑出 / 滑入的动画途中保留覆盖层投影 —— 覆盖层高度还包含
     * 正在移动的选择器，阴影会先原地滞留一块再突然消失。
     */
    private void applyNavShadow() {
        boolean glassOn = AppearanceManager.navGlassStyle(this) != AppearanceManager.GLASS_NONE;
        boolean floating = AppearanceManager.isFloatingNav(this);
        boolean merged = pagerShown;
        if (navShell != null) {
            navShell.setCardElevation(!merged && floating && !glassOn ? px(3) : 0);
        }
        applyOverlayElevation(merged && floating && !glassOn ? px(3) : 0);
    }

    /**
     * 合并态下把投影交给覆盖层整体。
     *
     * <p>轮廓要按底栏的外边距内缩，并扣掉底栏下外边距，否则阴影会比胶囊多出一段。
     * 覆盖层的 {@code clipChildren} 已关闭，阴影才能画到自身边界之外。
     */
    private void applyOverlayElevation(int elevationPx) {
        if (overlayContent == null) {
            return;
        }
        if (elevationPx <= 0) {
            overlayContent.setElevation(0f);
            return;
        }
        final int inset = AppearanceManager.isFloatingNav(this) ? px(NAV_MARGIN_H_DP) : 0;
        final int bottomGap = navShell != null
                && navShell.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) navShell.getLayoutParams()).bottomMargin : 0;
        final int radius = navRadiusPx();
        overlayContent.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(inset, 0,
                        Math.max(inset + 1, view.getWidth() - inset),
                        Math.max(1, view.getHeight() - bottomGap), radius);
            }
        });
        overlayContent.setClipToOutline(false);
        overlayContent.setElevation(elevationPx);
    }

    /**
     * 上圆角在两种形态之间平滑过渡。
     *
     * <p>页面选择器退场时若直接把上圆角从 0 改成胶囊半径，会在那一帧出现
     * 「直角突然变圆角」的跳变；用与滑出动画同时长、同曲线的补间即可消除。
     * 连续切换时从当前值续接，不跳回起点。
     */
    private void animateNavCornerTo(int targetPx, long durationMs) {
        if (navShell == null) {
            return;
        }
        int radius = navRadiusPx();
        int from = navTopCornerPx >= 0 ? navTopCornerPx : radius;
        if (navCornerAnimator != null) {
            navCornerAnimator.cancel();
            navCornerAnimator = null;
        }
        if (durationMs <= 0L || from == targetPx) {
            applyNavCorner(targetPx);
            return;
        }
        navCornerAnimator = ValueAnimator.ofInt(from, targetPx);
        navCornerAnimator.setDuration(durationMs);
        navCornerAnimator.setInterpolator(MotionManager.standard(this));
        navCornerAnimator.addUpdateListener(a -> applyNavCorner((int) a.getAnimatedValue()));
        navCornerAnimator.start();
    }

    /** 玻璃蒙版色：亚克力更厚实（高不透明度），液态玻璃更通透 */
    private int glassTint(int glass) {
        boolean liquid = glass == AppearanceManager.GLASS_LIQUID;
        int base = MaterialColors.getColor(navShell, liquid
                ? com.google.android.material.R.attr.colorSurfaceContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh);
        return withAlpha(base, liquid ? 0.42f : 0.80f);
    }

    /** 玻璃高光描边：仅液态玻璃使用，模拟玻璃棱边 */
    private int glassRim(int glass) {
        if (glass != AppearanceManager.GLASS_LIQUID) {
            return Color.TRANSPARENT;
        }
        return withAlpha(MaterialColors.getColor(navShell,
                com.google.android.material.R.attr.colorOutline), 0.55f);
    }

    private static int withAlpha(int color, float alpha) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        return (color & 0x00FFFFFF) | (Math.round(255f * clamped) << 24);
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void requestOverlayBlurUpdate() {
        if (overlayBlur != null) {
            overlayBlur.markDirty();
        }
    }

    // ------------------------------------------------------------------
    // 预见性返回（Android 13+ Predictive Back）
    // ------------------------------------------------------------------

    /**
     * 注册返回回调，让「我的贡献 → 歌曲百科」的返回也走手势进度驱动动画：
     * 手势进行中页面随手势位移，取消时回弹，确认时才真正切换目的地。
     * 非「我的贡献」目的地时该回调保持禁用，交还给系统完成 Activity 退场动画。
     */
    private void setupPredictiveBack() {
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackProgressed(@NonNull BackEventCompat backEvent) {
                int width = pageContributions.getWidth();
                if (width <= 0) {
                    return;
                }
                boolean fromLeft = backEvent.getSwipeEdge() == BackEventCompat.EDGE_LEFT;
                float progress = Math.max(0f, Math.min(1f, backEvent.getProgress()));
                float shift = progress * width * 0.25f;
                pageContributions.setTranslationX(fromLeft ? shift : -shift);
                pageContributions.setAlpha(1f - progress * 0.25f);
            }

            @Override
            public void handleOnBackCancelled() {
                resetContributionsOffset(true);
            }

            @Override
            public void handleOnBackPressed() {
                resetContributionsOffset(false);
                // selectBottomNavItem 只改选中态，不会回调 OnItemSelectedListener，
                // 因此这里必须显式切换目的地，否则会「选中态已回到百科、页面仍停在贡献」。
                selectBottomNavItem(DEST_WIKI);
                switchDestination(DEST_WIKI);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private void resetContributionsOffset(boolean animated) {
        if (pageContributions == null) {
            return;
        }
        pageContributions.animate().cancel();
        if (animated) {
            pageContributions.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(MotionManager.controlDuration(this))
                    .setInterpolator(MotionManager.standard(this))
                    .start();
        } else {
            pageContributions.setTranslationX(0f);
            pageContributions.setAlpha(1f);
        }
    }

    // ------------------------------------------------------------------
    // 「我的贡献」页面搭建
    // ------------------------------------------------------------------

    private void setupContributions() {
        groupTransport = findViewById(R.id.group_transport);
        tabLayout = findViewById(R.id.tab_layout);
        chipStatus = findViewById(R.id.chip_status);
        recyclerView = findViewById(R.id.recycler_view);
        contribScroll = findViewById(R.id.contrib_scroll);
        contribSwipe = findViewById(R.id.contrib_swipe);

        transportCard = findViewById(R.id.contrib_transport_card);
        accountTitle = findViewById(R.id.tv_account_title);
        accountCard = findViewById(R.id.contrib_account_card);
        searchCard = findViewById(R.id.contrib_search_card);

        pagerBar = findViewById(R.id.contrib_pager);
        btnPagePrev = findViewById(R.id.btn_page_prev);
        btnPageNext = findViewById(R.id.btn_page_next);
        btnPageFirst = findViewById(R.id.btn_page_first);
        btnPageLast = findViewById(R.id.btn_page_last);
        tvPageInfo = findViewById(R.id.tv_page_info);

        tilSearchKeyword = findViewById(R.id.til_search_keyword);
        etSearchKeyword = findViewById(R.id.et_search_keyword);
        rowSearchDate = findViewById(R.id.row_search_date);
        tvSearchDate = findViewById(R.id.tv_search_date);
        btnSearchApply = findViewById(R.id.btn_search_apply);
        btnSearchReset = findViewById(R.id.btn_search_reset);
        tvSearchResult = findViewById(R.id.tv_search_result);

        searchNormal = findViewById(R.id.search_normal);
        searchCompact = findViewById(R.id.search_compact);
        tilSearchKeywordCompact = findViewById(R.id.til_search_keyword_compact);
        etSearchKeywordCompact = findViewById(R.id.et_search_keyword_compact);
        btnSearchDateCompact = findViewById(R.id.btn_search_date_compact);
        btnSearchApplyCompact = findViewById(R.id.btn_search_apply_compact);

        ivAvatar = findViewById(R.id.iv_avatar);
        tvAccountName = findViewById(R.id.tv_account_name);
        tvAccountUid = findViewById(R.id.tv_account_uid);
        tvAccountStats = findViewById(R.id.tv_account_stats);
        btnAccountAction = findViewById(R.id.btn_account_action);

        stateLoading = findViewById(R.id.contrib_loading);
        stateEmpty = findViewById(R.id.contrib_empty);
        stateError = findViewById(R.id.contrib_error);
        emptyTitle = findViewById(R.id.contrib_empty_title);
        emptyDesc = findViewById(R.id.contrib_empty_desc);
        errorMessage = findViewById(R.id.contrib_error_message);
        errorAction = findViewById(R.id.contrib_error_action);

        errorAction.setOnClickListener(v -> {
            HapticsManager.tap(v);
            if (errorActionGoLogin) {
                loginLauncher.launch(new Intent(this, LoginActivity.class));
            } else {
                reload();
            }
        });
        btnAccountAction.setOnClickListener(v -> {
            HapticsManager.tap(v);
            onAccountAction();
        });

        buildTransportOptions();
        buildTypeTabs();
        buildStatusChips();

        adapter = new ContributionAdapter(this::onContributionClick);
        adapter.setLongClickListener(this::onContributionLongClick);
        coversSettingSnapshot = WikiPrefs.showContributionCovers(this);
        adapter.setShowCovers(coversSettingSnapshot);
        final int spanCount = Math.max(1, getResources().getInteger(R.integer.list_span_count));
        recyclerView.setLayoutManager(new GridLayoutManager(this, spanCount));
        recyclerView.setHasFixedSize(false);
        // 翻页 / 切分类时列表是整页替换，ItemAnimator 的「移除 + 新增」动画会与 DiffUtil
        // 的差异更新叠加成一次可见的闪烁；这里关闭条目动画，更新即时、稳定。
        recyclerView.setItemAnimator(null);
        recyclerView.setAdapter(adapter);
        // 内容不足一屏时也允许触发超限回弹，保证「所有状态下」交互一致
        if (contribScroll != null) {
            contribScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        btnPagePrev.setOnClickListener(v -> {
            HapticsManager.swipe(v);
            if (currentPage > 1) {
                navigateToPage(currentPage - 1);
            }
        });
        btnPageNext.setOnClickListener(v -> {
            HapticsManager.swipe(v);
            if (currentPage < totalPages) {
                navigateToPage(currentPage + 1);
            }
        });
        // 首 / 末页快捷跳转（需求 4）
        btnPageFirst.setOnClickListener(v -> {
            HapticsManager.swipe(v);
            if (currentPage > 1) {
                navigateToPage(1);
            }
        });
        btnPageLast.setOnClickListener(v -> {
            HapticsManager.swipe(v);
            if (currentPage < totalPages) {
                navigateToPage(totalPages);
            }
        });
        // 点击当前页码展开页码选择面板（需求 4）
        tvPageInfo.setOnClickListener(v -> {
            HapticsManager.tap(v);
            showPagePicker();
        });
        // Expressive 按压反馈：按下轻微收缩、抬起回弹，与其它控件的触感节奏一致
        tvPageInfo.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(120L)
                            .setInterpolator(MotionManager.standard(this)).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(MotionManager.standard(this)).start();
                    break;
                default:
                    break;
            }
            // 不消费事件，点击回调仍然照常触发
            return false;
        });

        setupSwipeRefresh();
        setupSearch();
        applyCardVisibility();
        // 显示设置改动后立即刷新卡片显隐，避免「改完要等一会儿才生效」
        WikiPrefs.get(this).registerOnSharedPreferenceChangeListener(displayPrefListener);

        // 滚动时按下一帧重建一次采样，实现动态模糊跟随而非静态贴图。
        if (contribScroll != null) {
            contribScroll.setOnScrollChangeListener(
                    (NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldX, oldY) -> {
                        if (scrollY != oldY) {
                            requestOverlayBlurUpdate();
                        }
                    });
        }
    }

    // ------------------------------------------------------------------
    // 原生下拉刷新（需求 2）
    // ------------------------------------------------------------------

    /**
     * 下拉刷新：使用 MD3 设计语言原生的圆形旋转指示器（{@link SwipeRefreshLayout}），
     * 触发瞬间同步播放「滑动」场景的系统振动反馈，与平台交互规范保持一致。
     */
    private void setupSwipeRefresh() {
        if (contribSwipe == null) {
            return;
        }
        int primary = MaterialColors.getColor(contribSwipe,
                androidx.appcompat.R.attr.colorPrimary);
        int container = MaterialColors.getColor(contribSwipe,
                com.google.android.material.R.attr.colorSurfaceContainerHigh);
        contribSwipe.setColorSchemeColors(primary);
        contribSwipe.setProgressBackgroundColorSchemeColor(container);
        contribSwipe.setOnRefreshListener(() -> {
            HapticsManager.swipe(contribSwipe);
            reload();
        });
    }

    /** 任何一次加载有了结果（内容 / 空 / 错误）都要收起下拉指示器 */
    private void stopRefresh() {
        if (contribSwipe != null && contribSwipe.isRefreshing()) {
            contribSwipe.setRefreshing(false);
        }
    }

    // ------------------------------------------------------------------
    // 顶部卡片显隐（需求 3 / 6）
    // ------------------------------------------------------------------

    /**
     * 按显示设置应用各全宽卡片的显隐。
     *
     * <p>需求 6 的「下方内容自动上移」由布局本身保证：这些卡片同处一个纵向
     * {@link android.widget.LinearLayout}，{@link View#GONE} 的视图不参与测量与布局，
     * 其后的 Tab / 筛选 / 列表会自然上移填充空出的空间；下拉刷新外壳以
     * {@code layout_weight=1} 撑满剩余高度，因此无论隐藏到何种程度都始终可下拉。
     */
    private void applyCardVisibility() {
        transportCardSnapshot = WikiPrefs.showTransportCard(this);
        accountCardSnapshot = WikiPrefs.showAccountCard(this);
        searchCardSnapshot = WikiPrefs.showContributionSearch(this);
        if (transportCard != null) {
            transportCard.setVisibility(transportCardSnapshot ? View.VISIBLE : View.GONE);
        }
        if (accountTitle != null) {
            accountTitle.setVisibility(accountCardSnapshot ? View.VISIBLE : View.GONE);
        }
        if (accountCard != null) {
            accountCard.setVisibility(accountCardSnapshot ? View.VISIBLE : View.GONE);
        }
        if (searchCard != null) {
            searchCard.setVisibility(searchCardSnapshot ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // 分页交互组件（需求 4）
    // ------------------------------------------------------------------

    /**
     * 页码选择面板：以 MD3 单选子菜单列出全部页码。
     *
     * <p>面板内只保留页码本身 —— 「跳转第一页 / 最后一页」已由外部功能图标承担，
     * 重复列出既冗余又会让图标行显得拥挤重叠，因此这里彻底移除。
     */
    private void showPagePicker() {
        if (totalPages <= 1) {
            HapticsManager.reject(tvPageInfo);
            return;
        }
        final int pages = totalPages;
        final String[] items = new String[pages];
        for (int i = 0; i < pages; i++) {
            items[i] = getString(R.string.contrib_page_current, i + 1);
        }
        int checked = Math.max(0, Math.min(pages - 1, currentPage - 1));

        SheetList.singleChoice(this, getString(R.string.contrib_page_picker_title, pages),
                items, checked, which -> {
                    int target = which + 1;
                    if (target != currentPage) {
                        HapticsManager.swipe(tvPageInfo);
                        navigateToPage(target);
                    }
                });
    }

    // ------------------------------------------------------------------
    // 贡献信息搜索（需求 5）
    // ------------------------------------------------------------------

    private void setupSearch() {
        if (etSearchKeyword != null) {
            etSearchKeyword.setOnEditorActionListener((v, actionId, event) -> {
                hideKeyboard();
                applySearch();
                return true;
            });
        }
        if (tilSearchKeyword != null) {
            // 清空按钮：除了清空输入，同时立即恢复未过滤的列表
            tilSearchKeyword.setEndIconOnClickListener(v -> {
                if (etSearchKeyword != null) {
                    etSearchKeyword.setText("");
                }
                HapticsManager.toggle(v);
                applySearch();
            });
        }
        if (rowSearchDate != null) {
            rowSearchDate.setOnClickListener(v -> {
                HapticsManager.tap(v);
                showDateRangePicker();
            });
        }
        if (btnSearchApply != null) {
            btnSearchApply.setOnClickListener(v -> {
                HapticsManager.tap(v);
                applySearch();
            });
        }
        if (btnSearchReset != null) {
            btnSearchReset.setOnClickListener(v -> {
                HapticsManager.tap(v);
                resetSearch();
            });
        }
        // 精简单行布局：业务逻辑与普通布局完全一致，只是控件换了一套
        if (etSearchKeywordCompact != null) {
            etSearchKeywordCompact.setOnEditorActionListener((v, actionId, event) -> {
                hideKeyboard();
                applySearch();
                return true;
            });
        }
        if (tilSearchKeywordCompact != null) {
            // 单行布局把「重置」合并为输入框右侧常驻图标，清空关键词与日期并重新加载
            tilSearchKeywordCompact.setEndIconOnClickListener(v -> {
                HapticsManager.toggle(v);
                resetSearch();
            });
        }
        if (btnSearchDateCompact != null) {
            btnSearchDateCompact.setOnClickListener(v -> {
                HapticsManager.tap(v);
                showDateRangePicker();
            });
        }
        if (btnSearchApplyCompact != null) {
            btnSearchApplyCompact.setOnClickListener(v -> {
                HapticsManager.tap(v);
                applySearch();
            });
        }
        applySearchLayout();
        updateSearchDateLabel();
    }

    /**
     * 按设置切换「普通竖向」与「精简单行」两种搜索卡片布局。
     *
     * <p>切换时把已输入的关键词在两套输入框之间带过去，避免用户切完布局还要重新输入；
     * 仅在模式真的变化时搬运，避免覆盖用户正在编辑的内容。
     */
    private void applySearchLayout() {
        boolean compact = WikiPrefs.compactSearchLayout(this);
        if (compact != searchCompactMode) {
            EditText from = compact ? etSearchKeyword : etSearchKeywordCompact;
            EditText to = compact ? etSearchKeywordCompact : etSearchKeyword;
            if (from != null && to != null) {
                Editable text = from.getText();
                String value = text == null ? "" : text.toString();
                to.setText(value);
                to.setSelection(value.length());
            }
        }
        searchCompactMode = compact;
        if (searchNormal != null) {
            searchNormal.setVisibility(compact ? View.GONE : View.VISIBLE);
        }
        if (searchCompact != null) {
            searchCompact.setVisibility(compact ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 提交日期筛选：与专辑上传页使用<b>同一个单日选择器</b>（{@code datePicker}）。
     *
     * <p>此前这里用 {@code dateRangePicker}，虽然同属 MD3，但双栏头部 / 区间高亮的形态
     * 与专辑上传页的单日选择器完全不同。现在改为「先选开始、再选结束」的两步单日选择，
     * 控件样式与专辑上传页完全一致。
     */
    private void showDateRangePicker() {
        Long start = searchDateFrom > 0 ? localDayStartUtc(searchDateFrom) : null;
        pickSingleDate(R.string.contrib_search_date_from, start, first -> {
            Long end = searchDateTo > 0 ? localDayStartUtc(searchDateTo) : first;
            // 等第一个选择器完全关闭后再弹第二个，避免两个 DialogFragment 抢占事务
            getWindow().getDecorView().post(() ->
                    pickSingleDate(R.string.contrib_search_date_to, end, second -> {
                        long from = first;
                        long to = second;
                        if (to < from) {
                            long swap = from;
                            from = to;
                            to = swap;
                        }
                        final long finalFrom = from;
                        final long finalTo = to;
                        searchDateFrom = localDayStart(finalFrom);
                        searchDateTo = localDayEnd(finalTo);
                        updateSearchDateLabel();
                        HapticsManager.confirm(rowSearchDate);
                        applySearch();
                    }));
        });
    }

    /** 单日选择器：参数与回填时区与专辑上传页的发行日期选择保持一致。 */
    private void pickSingleDate(@StringRes int titleRes, @Nullable Long selection,
                                java.util.function.Consumer<Long> onPick) {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setTitleText(titleRes);
        if (selection != null) {
            builder.setSelection(selection);
        }
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(onPick::accept);
        picker.show(getSupportFragmentManager(), "contrib-date-" + titleRes);
    }

    /**
     * 提交检索。
     *
     * <p>服务端接口<b>没有关键词参数</b>，因此只能（也只能）在客户端完成匹配：
     * 先把该分类 / 状态下的贡献逐页全量扫一遍，再在扫描结果上做本地过滤。
     * 这正是「关键词搜索看起来完全无效」的根因所在 —— 旧实现只过滤当前页的 20 条，
     * 绝大多数关键词自然一条也匹配不到。
     *
     * <p>条件被清空（或解析为空）时退出检索模式，回到服务端分页。
     */
    private void applySearch() {
        // 只读取当前可见布局的输入，避免隐藏控件里的旧值覆盖用户刚输入的内容
        EditText active = searchCompactMode ? etSearchKeywordCompact : etSearchKeyword;
        if (active != null) {
            Editable text = active.getText();
            searchKeyword = text == null ? "" : text.toString().trim();
        }
        hideKeyboard();
        // 数据源（分类 / 状态 / 凭据）没变时复用上次的全量扫描，改关键词只做本地过滤，
        // 否则每敲一次搜索都要把几千条重新翻一遍，体感就是「搜索很慢」
        if (scanCache != null && !scanCache.isEmpty()) {
            cancelInflight();
            final int gen = ++generation;
            final List<Contribution> matches = filterContributions(scanCache);
            Log.d(TAG, "检索复用扫描缓存 共=" + scanCache.size() + " 命中=" + matches.size());
            renderSearchResult(gen, matches);
            return;
        }
        reload();
    }

    private void resetSearch() {
        searchKeyword = "";
        searchDateFrom = 0;
        searchDateTo = 0;
        if (etSearchKeyword != null) {
            etSearchKeyword.setText("");
        }
        if (etSearchKeywordCompact != null) {
            etSearchKeywordCompact.setText("");
        }
        updateSearchDateLabel();
        hideKeyboard();
        reload();
    }

    /** 当前是否存在生效的搜索条件 */
    private boolean isSearchActive() {
        return !searchKeyword.isEmpty() || (searchDateFrom > 0 && searchDateTo > 0);
    }

    /** 按关键词（名称 / 歌手，忽略大小写的模糊匹配）与提交日期范围过滤条目 */
    private List<Contribution> filterContributions(List<Contribution> source) {
        List<Contribution> out = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return out;
        }
        String keyword = searchKeyword.toLowerCase(Locale.ROOT);
        boolean byKeyword = !keyword.isEmpty();
        boolean byDate = searchDateFrom > 0 && searchDateTo > 0;
        if (!byKeyword && !byDate) {
            out.addAll(source);
            return out;
        }
        for (Contribution item : source) {
            if (item == null) {
                continue;
            }
            if (byKeyword) {
                String name = item.displayName().toLowerCase(Locale.ROOT);
                String artist = item.displayArtist().toLowerCase(Locale.ROOT);
                if (!name.contains(keyword) && !artist.contains(keyword)) {
                    continue;
                }
            }
            if (byDate && (item.createTime <= 0
                    || item.createTime < searchDateFrom || item.createTime > searchDateTo)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /**
     * 把当前应当展示的条目提交给列表。
     *
     * <p>两种模式：
     * <ul>
     *   <li><b>检索模式</b>（{@link #searching}）：数据源是命中集
     *       {@link #searchMatches}，按展示分页就地切片 —— 总页数由命中集规模决定，
     *       与服务端全量页数彻底解耦；</li>
     *   <li><b>普通模式</b>：数据源是服务端当前页的原始条目，仅做条件兜底过滤。</li>
     * </ul>
     */
    private void submitFiltered() {
        if (adapter == null) {
            return;
        }
        List<Contribution> filtered;
        if (searching) {
            filtered = pageSlice(searchMatches, currentPage);
            updateSearchResultLabel(searchMatches.size());
        } else {
            filtered = filterContributions(lastPageItems);
            if (isSearchActive()) {
                updateSearchResultLabel(filtered.size());
            } else if (tvSearchResult != null) {
                tvSearchResult.setVisibility(View.GONE);
            }
        }
        // 未读审核结果置顶；同组内按发布时间倒序（与服务端默认顺序一致）
        final Set<String> unreadKeys = AuditInbox.unreadKeys(this);
        filtered.sort((a, b) -> {
            boolean unreadA = a != null && unreadKeys.contains(a.stableKey());
            boolean unreadB = b != null && unreadKeys.contains(b.stableKey());
            if (unreadA != unreadB) {
                return unreadA ? -1 : 1;
            }
            long timeA = a == null ? 0L : a.createTime;
            long timeB = b == null ? 0L : b.createTime;
            return Long.compare(timeB, timeA);
        });
        // 封面可用性按数据直接判定：歌词 / 翻译等类型本身没有封面 URL，
        // 不能再依赖异步加载回调计数——上一页迟到的回调会把本页误判成「全部失败」，
        // 表现为切换标签后封面整体消失、只有手动刷新才恢复。
        boolean hasCover = false;
        for (Contribution item : filtered) {
            if (item != null && CoverLoader.isAllowed(item.coverUrl)) {
                hasCover = true;
                break;
            }
        }
        adapter.setCoversUnavailable(!hasCover);
        coversSettingSnapshot = WikiPrefs.showContributionCovers(this);
        adapter.setShowCovers(coversSettingSnapshot);
        // 预热必须先于提交：submitList 的 diff 在后台线程算完后立刻回主线程重绑，
        // 预热晚一步就会让首帧只能先画占位图，形成「封面慢半拍 / 闪一下」。
        preloadCovers(filtered);
        adapter.submitList(new ArrayList<>(filtered));
    }

    /** 从命中集中裁出指定页（页码从 1 开始），越界返回空列表 */
    private static List<Contribution> pageSlice(List<Contribution> source, int page) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        int from = (Math.max(1, page) - 1) * NetEaseWikiClient.PAGE_SIZE;
        if (from >= source.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(source.size(), from + NetEaseWikiClient.PAGE_SIZE);
        return new ArrayList<>(source.subList(from, to));
    }

    private void updateSearchResultLabel(int count) {
        if (tvSearchResult == null) {
            return;
        }
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(getString(R.string.contrib_search_result, count));
    }

    /** 全量扫描期间的进度提示（复用在搜索结果行上） */
    private void showSearchProgress(int scanned) {
        if (tvSearchResult == null) {
            return;
        }
        tvSearchResult.setVisibility(View.VISIBLE);
        tvSearchResult.setText(scanned <= 0
                ? getString(R.string.contrib_search_scanning)
                : getString(R.string.contrib_search_progress, scanned));
    }

    // ------------------------------------------------------------------
    // 检索：客户端全量扫描 + 命中集本地分页
    // ------------------------------------------------------------------

    /** 扫描进度回调（后台线程），参数为累计扫描到的条目数 */
    private interface ScanProgress {
        void onScanned(int count);
    }

    /**
     * 执行一次检索：全量扫描 → 本地过滤 → 命中集分页渲染。
     *
     * <p>扫描结果缓存在 {@link #scanCache}，同一套分类 / 状态 / 凭据下改关键词
     * 或改日期范围无需重新翻页。
     */
    private void startSearch() {
        if (!SessionManager.isLoggedIn(this)) {
            cancelInflight();
            generation++;
            currentPage = 1;
            totalPages = 1;
            cookieSnapshot = "";
            clearListState();
            refreshAccountCard();
            showError(getString(R.string.contrib_error_not_logged_in), true);
            return;
        }

        cancelInflight();
        final int gen = ++generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;
        cookieSnapshot = SessionManager.cookie(this);
        if (pendingScrollY < 0 && contribScroll != null) {
            pendingScrollY = contribScroll.getScrollY();
        }
        refreshAccountCard();
        showSearchProgress(0);
        showLoading();

        new Thread(() -> {
            try {
                List<Contribution> all = scanCache;
                if (all == null) {
                    all = scanAllPages(token, scanned -> runOnUiThread(() -> {
                        if (gen == generation && !isFinishing() && !isDestroyed()) {
                            showSearchProgress(scanned);
                        }
                    }));
                    if (gen != generation) {
                        Log.d(TAG, "检索扫描结果已被丢弃 gen=" + gen);
                        return;
                    }
                    scanCache = all;
                }
                final List<Contribution> matches = filterContributions(all);
                runOnUiThread(() -> renderSearchResult(gen, matches));
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    Log.d(TAG, "检索已取消 gen=" + gen);
                    return;
                }
                runOnUiThread(() -> handleFailure(gen, e));
            } catch (final Throwable e) {
                Log.e(TAG, "检索出现未预期异常 gen=" + gen, e);
                runOnUiThread(() -> handleFailure(gen, null));
            }
        }, "wiki-contrib-search").start();
    }

    /**
     * 逐页扫描当前分类 / 状态下的全部贡献。
     *
     * <p>扫描页大小（{@link #SEARCH_SCAN_PAGE_SIZE}）大于展示分页，以减少往返次数；
     * 按 {@link Contribution#stableKey()} 去重，避免「全部」聚合页的重复条目；
     * 页数受 {@link #SEARCH_SCAN_MAX_PAGES} 约束，超大账号也不会无限翻页。
     */
    private List<Contribution> scanAllPages(Http.CancelToken token, @Nullable ScanProgress progress)
            throws WikiException {
        List<Contribution> all = NetEaseWikiClient.scanAll(this, currentType, currentStatus,
                SEARCH_SCAN_PAGE_SIZE, SEARCH_SCAN_MAX_PAGES, token,
                count -> {
                    if (progress != null) {
                        progress.onScanned(count);
                    }
                });
        Log.d(TAG, "检索扫描完成 type=" + currentType + " status=" + currentStatus
                + " 共扫描=" + all.size());
        return all;
    }

    /** 命中集就绪：写入分页状态并渲染第 1 页 */
    private void renderSearchResult(int gen, List<Contribution> matches) {
        if (gen != generation || isFinishing() || isDestroyed()) {
            return;
        }
        inflight = null;
        searching = true;
        searchMatches = matches;
        currentPage = 1;
        // 总页数只由命中集规模决定，不再沿用服务端全量页数
        totalPages = Math.max(1,
                (matches.size() + NetEaseWikiClient.PAGE_SIZE - 1) / NetEaseWikiClient.PAGE_SIZE);
        updatePager(matches.size());
        submitFiltered();
        if (matches.isEmpty()) {
            showEmpty();
        } else {
            showContent();
            restoreContribScroll();
        }
    }

    /** 检索模式下的翻页：命中集已在内存，纯本地切片，不再请求服务端 */
    private void showSearchPage(int page) {
        int target = Math.max(1, Math.min(totalPages, page));
        if (target == currentPage) {
            return;
        }
        pageNavPending = false;
        currentPage = target;
        updatePager(searchMatches.size());
        // 本地换页是同步的：先淡出旧页，再提交新页数据并淡入，避免标题与列表瞬间跳变
        MotionManager.pageSwap(this, recyclerView, this::submitFiltered);
        showContent();
    }

    /**
     * 预热本页封面：列表渲染完立即把封面拉进磁盘 + 内存缓存，
     * 滚动到该条目时直接命中缓存，从而消除滚动过程中的空白与补图延迟。
     */
    private void preloadCovers(List<Contribution> items) {
        if (items == null || items.isEmpty() || adapter == null || !adapter.isShowCovers()) {
            return;
        }
        int size = getResources().getDimensionPixelSize(R.dimen.list_cover_size);
        List<String> urls = new ArrayList<>(items.size());
        for (Contribution item : items) {
            if (item != null && CoverLoader.isAllowed(item.coverUrl)) {
                urls.add(item.coverUrl);
            }
        }
        CoverLoader.get().preload(urls, size);
    }

    private void updateSearchDateLabel() {
        boolean hasRange = searchDateFrom > 0 && searchDateTo > 0;
        if (tvSearchDate != null) {
            if (hasRange) {
                tvSearchDate.setText(getString(R.string.contrib_search_date_range,
                        formatDay(searchDateFrom), formatDay(searchDateTo)));
            } else {
                tvSearchDate.setText(R.string.contrib_search_date_none);
            }
        }
        // 单行布局没有日期文案位，用图标着色 + 无障碍描述表达「是否已设置范围」
        if (btnSearchDateCompact instanceof ImageView) {
            int color = MaterialColors.getColor(btnSearchDateCompact, hasRange
                    ? androidx.appcompat.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            ((ImageView) btnSearchDateCompact).setImageTintList(
                    ColorStateList.valueOf(color));
            btnSearchDateCompact.setContentDescription(hasRange
                    ? getString(R.string.contrib_search_date_range,
                            formatDay(searchDateFrom), formatDay(searchDateTo))
                    : getString(R.string.contrib_search_date_title));
        }
    }

    private String formatDay(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
    }

    /**
     * 选择器返回值的时区换算：其入参 / 返回值都是「所选日期在 UTC 的零点」。
     * 换算到本地再参与比较，才能正确匹配服务端返回的本地时间戳。
     */
    private static long localDayStart(long utcMillis) {
        return utcMillis - TimeZone.getDefault().getOffset(utcMillis);
    }

    private static long localDayEnd(long utcMillis) {
        return localDayStart(utcMillis) + 24L * 60L * 60L * 1000L - 1L;
    }

    private static long localDayStartUtc(long localMillis) {
        return localMillis + TimeZone.getDefault().getOffset(localMillis);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            focus = etSearchKeyword;
        }
        if (focus == null) {
            return;
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        focus.clearFocus();
    }

    /** 接口通道单选项：与原 APK {@code Lt1/d} 一致，切换后立即按新通道重新拉取 */
    private void buildTransportOptions() {
        groupTransport.removeAllViews();
        TransportMode selected = WikiPrefs.transportMode(this);
        for (TransportMode mode : TransportMode.values()) {
            MaterialRadioButton button = new MaterialRadioButton(this);
            button.setId(View.generateViewId());
            button.setText(mode.labelRes());
            button.setTag(mode);
            button.setChecked(mode == selected);
            groupTransport.addView(button);
        }
        // 先完成勾选再挂监听，避免初始化阶段触发一次多余请求
        groupTransport.setOnCheckedChangeListener((group, checkedId) -> {
            View checked = group.findViewById(checkedId);
            Object tag = checked == null ? null : checked.getTag();
            if (!(tag instanceof TransportMode)) {
                return;
            }
            TransportMode mode = (TransportMode) tag;
            if (mode == WikiPrefs.transportMode(this)) {
                return;
            }
            HapticsManager.toggle(groupTransport);
            WikiPrefs.setTransportMode(this, mode);
            reload();
        });
    }

    /** 分类 Tab：与 {@link NetEaseWikiClient#TYPE_LABELS} 一一对应 */
    private void buildTypeTabs() {
        tabLayout.removeAllTabs();
        for (String label : NetEaseWikiClient.TYPE_LABELS) {
            tabLayout.addTab(tabLayout.newTab().setText(label));
        }
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position < 0 || position >= NetEaseWikiClient.TYPE_VALUES.length) {
                    return;
                }
                HapticsManager.toggle(tabLayout);
                currentType = NetEaseWikiClient.TYPE_VALUES[position];
                // 长按跳转时由 applyFocus 自己加载目标页，这里不要再多打一次第 1 页
                if (suppressReload) {
                    return;
                }
                // 不再对整页做 alpha 淡入：整块内容半透明再恢复会被感知为「整页闪一下」。
                // 数据到达后由 DiffUtil 做差异更新，页面本身保持稳定。
                reload();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // 重复点击当前分类视为手动刷新
                HapticsManager.toggle(tabLayout);
                reload();
            }
        });
    }

    /** 审核状态筛选：与 {@link NetEaseWikiClient#STATUS_LABELS} 一一对应 */
    private void buildStatusChips() {
        chipStatus.removeAllViews();
        for (int i = 0; i < NetEaseWikiClient.STATUS_LABELS.length; i++) {
            // 使用主题默认 chipStyle，可勾选状态由 ChipGroup 的 singleSelection 保证
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(NetEaseWikiClient.STATUS_LABELS[i]);
            chip.setCheckable(true);
            chip.setClickable(true);
            chip.setTag(NetEaseWikiClient.STATUS_VALUES[i]);
            chipStatus.addView(chip);
        }
        // 先选中默认项再挂监听，避免初始化阶段触发一次多余请求
        if (chipStatus.getChildCount() > 0) {
            ((Chip) chipStatus.getChildAt(0)).setChecked(true);
        }
        chipStatus.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            View checked = group.findViewById(checkedIds.get(0));
            Object tag = checked == null ? null : checked.getTag();
            currentStatus = tag instanceof Integer ? (Integer) tag : null;
            HapticsManager.toggle(chipStatus);
            if (suppressReload) {
                return;
            }
            reload();
        });
    }

    /** 选中对应 tab（若未选中，其选中回调会自动触发加载） */
    private void selectTabForType(int type) {
        for (int i = 0; i < NetEaseWikiClient.TYPE_VALUES.length; i++) {
            if (NetEaseWikiClient.TYPE_VALUES[i] == type) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null && !tab.isSelected()) {
                    tab.select();
                }
                return;
            }
        }
    }

    /**
     * 贡献卡片跳转（需求 2：URL 标准化）。
     *
     * 跳转目标必须由<b>条目自身</b>的真实类型决定，而不是当前「分类」标签：
     * 「全部」标签页混排了歌曲 / 专辑 / 艺人 / 歌词 / 翻译，若沿用 {@code currentType}
     * （「全部」= 0，走 default 分支）就会把所有链接都拼成 {@code /song?id=}，
     * 这正是「全部页所有链接都是 song」的根因。
     *
     * <p>{@link Contribution#routeType()} 优先取条目 {@code type}，歌词 / 翻译（{@code type=0}）
     * 回落到来源查询类型，从而与其它标签页生成完全一致的链接。
     */
    private void onContributionClick(Contribution item) {
        if (item == null) {
            return;
        }
        // 用户已查看该条审核结果：清除「新」标记与对应未读消息
        AuditInbox.markRead(this, item.stableKey());
        refreshUnreadState();
        // 立即按发布时间归位，避免已读卡片仍停在顶部
        if (destination == DEST_CONTRIBUTIONS && currentStateTag == STATE_CONTENT
                && lastPageItems != null) {
            submitFiltered();
        }
        long id = WikiIdParser.parse(item.targetId);
        String url = WikiLinks.urlFor(item.routeType(), id);
        if (url != null) {
            WikiLinks.open(this, url);
        } else {
            Feedback.show(findViewById(R.id.bottom_overlay), item.displayName());
        }
    }

    /**
     * 长按贡献卡片：复制该条目的分享链接（第二阶段新增的长按交互，
     * 与长按场景的差异化触感一起构成完整的交互语义）。
     */
    private void onContributionLongClick(Contribution item) {
        if (item == null) {
            return;
        }
        long id = WikiIdParser.parse(item.targetId);
        String url = WikiLinks.urlFor(item.routeType(), id);
        if (url == null) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(item.displayName(), url));
            Feedback.show(findViewById(R.id.bottom_overlay), R.string.contrib_link_copied);
        } catch (Exception e) {
            Log.w(TAG, "复制链接失败", e);
        }
    }

    // ------------------------------------------------------------------
    // 封面显示控制（需求 4）
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 账号卡片
    // ------------------------------------------------------------------

    private void onAccountAction() {
        if (SessionManager.isLoggedIn(this)) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.login_logout_title)
                    .setMessage(R.string.login_logout_msg)
                    .setNegativeButton(R.string.back, null)
                    .setPositiveButton(R.string.login_logout, (dialog, which) -> {
                        // 主动退出：清掉账号维度的审核数据，避免跨账号残留
                        SessionManager.clearAccountData(this);
                        SessionManager.clear(this);
                        statsGeneration++;
                        AuditPollingService.stop(this);
                        cookieSnapshot = "";
                        Feedback.show(findViewById(R.id.bottom_overlay),
                                R.string.login_logout_done);
                        reload();
                    })
                    .show();
        } else {
            loginLauncher.launch(new Intent(this, LoginActivity.class));
        }
    }

    private void refreshAccountCard() {
        boolean loggedIn = SessionManager.isLoggedIn(this);

        String name = SessionManager.displayName(this);
        if (name.isEmpty()) {
            name = getString(loggedIn
                    ? R.string.login_account_unknown : R.string.login_not_logged_in);
        }
        tvAccountName.setText(name);

        long userId = SessionManager.userId(this);
        tvAccountUid.setText(userId > 0
                ? getString(R.string.contrib_uid, String.valueOf(userId))
                : getString(R.string.contrib_uid_none));

        btnAccountAction.setText(loggedIn ? R.string.login_logout : R.string.login_cookie_submit);

        String avatar = SessionManager.avatarUrl(this);
        int size = getResources().getDimensionPixelSize(R.dimen.avatar_size);
        ivAvatar.setImageResource(R.drawable.ic_account_muted);
        if (loggedIn && CoverLoader.isAllowed(avatar)) {
            CoverLoader.get().load(ivAvatar, avatar, size);
        } else {
            ivAvatar.setTag(null);
        }

        if (!loggedIn) {
            statsGeneration++;
            cancelStatsCounter();
            statsUgcShown = -1;
            statsShellShown = -1;
            tvAccountStats.setVisibility(View.GONE);
            return;
        }
        tvAccountStats.setVisibility(View.VISIBLE);
        tvAccountStats.setText(R.string.contrib_stats_loading);
        loadStatsAsync();
    }

    /** 统计数字的计数器动效：从当前展示值滚到新值，读数过程符合 Expressive 的节奏。 */
    private void animateStats(int ugc, int shell) {
        int fromUgc = statsUgcShown < 0 ? 0 : statsUgcShown;
        int fromShell = statsShellShown < 0 ? 0 : statsShellShown;
        statsUgcShown = ugc;
        statsShellShown = shell;
        cancelStatsCounter();
        if (fromUgc == ugc && fromShell == shell) {
            tvAccountStats.setText(getString(R.string.contrib_stats, ugc, shell));
            return;
        }
        statsCounter = MotionManager.counter(this, fraction -> {
            int currentUgc = Math.round(fromUgc + (ugc - fromUgc) * fraction);
            int currentShell = Math.round(fromShell + (shell - fromShell) * fraction);
            tvAccountStats.setText(getString(R.string.contrib_stats, currentUgc, currentShell));
        });
    }

    private void cancelStatsCounter() {
        if (statsCounter != null) {
            statsCounter.cancel();
            statsCounter = null;
        }
    }

    /** 统计接口失败不影响列表展示，届时直接隐藏该行。 */
    private void loadStatsAsync() {
        final int gen = ++statsGeneration;
        new Thread(() -> {
            try {
                final NetEaseWikiClient.Stats stats =
                        NetEaseWikiClient.fetchStats(MainActivity.this, null);
                runOnUiThread(() -> {
                    if (gen != statsGeneration || isFinishing() || isDestroyed()) {
                        return;
                    }
                    tvAccountStats.setVisibility(View.VISIBLE);
                    animateStats(stats.ugcCount, stats.cloudShell);
                });
            } catch (final Exception e) {
                Log.w(TAG, "统计接口失败，隐藏统计行 gen=" + gen
                        + (e instanceof WikiException
                        ? " kind=" + ((WikiException) e).getKind() : ""), e);
                runOnUiThread(() -> {
                    if (gen != statsGeneration || isFinishing() || isDestroyed()) {
                        return;
                    }
                    tvAccountStats.setVisibility(View.GONE);
                });
            }
        }, "wiki-contrib-stats").start();
    }

    // ------------------------------------------------------------------
    // 列表加载（页码分页 + 代际校验 + 在途取消）
    // ------------------------------------------------------------------

    /**
     * 数据源变化后重新加载（分类 / 状态 / 通道 / 凭据变化，以及检索条件提交）。
     *
     * <p>数据源一变，之前的检索扫描缓存即失效；若当前检索条件仍然生效，
     * 直接按新数据源重跑一次检索，避免「条件还在、结果却退回未过滤的全量页」。
     */
    private void reload() {
        scanCache = null;
        pageCache.clear();
        searchMatches = Collections.emptyList();
        if (isSearchActive()) {
            startSearch();
        } else {
            searching = false;
            loadPage(1);
        }
    }

    /**
     * 用户主动翻页的唯一入口（上一页 / 下一页 / 首末页 / 页码面板）。
     *
     * <p>只有这条路径会触发「列表淡出 → 换页 → 淡入」的过渡；
     * 切分类 / 切状态属于数据源变化，不播放翻页过渡，避免整页闪动。
     */
    private void navigateToPage(int page) {
        int target = Math.max(1, Math.min(totalPages, page));
        if (target == currentPage) {
            return;
        }
        pageNavPending = true;
        loadPage(target);
    }

    /** 加载指定页（页码从 1 开始），整页替换列表内容 */
    private void loadPage(int page) {
        // 检索模式：命中集已完整在内存，翻页只做本地切片，不需要也不应该再请求服务端
        if (searching) {
            showSearchPage(page);
            return;
        }
        if (!SessionManager.isLoggedIn(this)) {
            cancelInflight();
            generation++;
            statsGeneration++;
            pageNavPending = false;
            cookieSnapshot = "";
            currentPage = 1;
            totalPages = 1;
            clearListState();
            refreshAccountCard();
            showError(getString(R.string.contrib_error_not_logged_in), true);
            return;
        }

        cancelInflight();
        final int gen = ++generation;
        final Http.CancelToken token = new Http.CancelToken();
        inflight = token;
        cookieSnapshot = SessionManager.cookie(this);
        final int targetPage = Math.max(1, page);
        // 滚动状态持久化：加载前记录当前位置；加载期间保留旧列表，不做清空/隐藏，
        // 避免滚动容器内容高度骤降导致系统把 scrollY 强制钳回 0（需求 2 的根因）。
        if (pendingScrollY < 0 && contribScroll != null) {
            pendingScrollY = contribScroll.getScrollY();
        }
        refreshAccountCard();
        // 仅「用户主动翻页」播放压暗过渡：切分类 / 切状态 / 下拉刷新保持稳定，避免整页闪动
        boolean paginating = pageNavPending && currentStateTag == STATE_CONTENT;
        pageNavPending = false;
        if (paginating) {
            listDimmed = true;
            MotionManager.dimList(this, recyclerView);
        }
        showLoading();
        fetch(gen, token, (targetPage - 1) * NetEaseWikiClient.PAGE_SIZE, targetPage);
    }

    private void fetch(final int gen, final Http.CancelToken token, final int offset,
                       final int page) {
        // 同一分类 / 状态下的页命中缓存时直接呈现：来回翻页不再等一次网络往返
        final String cacheKey = currentType + "/" + currentStatus + "/" + page;
        final NetEaseWikiClient.Page cached = pageCache.get(cacheKey);
        if (cached != null) {
            Log.d(TAG, "fetch 命中页缓存 page=" + page);
            renderFetchedPage(gen, page, cached);
            return;
        }
        Log.d(TAG, "fetch 开始 gen=" + gen + " page=" + page + " offset=" + offset
                + " type=" + currentType + " status=" + currentStatus);
        new Thread(() -> {
            try {
                // 「全部」标签页需要客户端聚合：服务端「全部」结果不含歌词 / 翻译，
                // 否则这两类内容永远不会出现在「全部」页（需求 1 的根因）。
                final NetEaseWikiClient.Page result =
                        currentType == NetEaseWikiClient.TYPE_ALL
                                ? NetEaseWikiClient.fetchAllTypesPage(MainActivity.this, currentStatus,
                                offset, NetEaseWikiClient.PAGE_SIZE, token)
                                : NetEaseWikiClient.fetchPage(MainActivity.this, currentType,
                                currentStatus, offset, NetEaseWikiClient.PAGE_SIZE, token);
                runOnUiThread(() -> {
                    pageCache.put(cacheKey, result);
                    renderFetchedPage(gen, page, result);
                });
            } catch (final WikiException e) {
                if (e.getKind() == WikiException.Kind.CANCELLED) {
                    Log.d(TAG, "fetch 已取消 gen=" + gen);
                    return;
                }
                Log.e(TAG, "fetch 失败 gen=" + gen + " kind=" + e.getKind()
                        + " httpCode=" + e.getHttpCode() + " msg=" + e.getMessage(), e);
                runOnUiThread(() -> handleFailure(gen, e));
            } catch (final Throwable e) {
                // 兜底捕获 Throwable（含 Error），保证查询过程绝不崩溃
                Log.e(TAG, "fetch 出现未预期异常 gen=" + gen, e);
                runOnUiThread(() -> handleFailure(gen, null));
            }
        }, "wiki-contributions").start();
    }

    /** 渲染一页服务端数据（网络返回与页缓存共用同一条路径）。 */
    private void renderFetchedPage(int gen, int page, NetEaseWikiClient.Page result) {
        try {
            if (gen != generation || isFinishing() || isDestroyed()) {
                Log.d(TAG, "fetch 结果已被丢弃（过期代际或页面销毁）gen=" + gen);
                return;
            }
            inflight = null;
            currentPage = page;
            // 聚合页的总页数不能由求和后的 total 推算，优先采用数据层给出的值
            totalPages = result.pageCount > 0
                    ? result.pageCount
                    : Math.max(1, (result.total + NetEaseWikiClient.PAGE_SIZE - 1)
                    / NetEaseWikiClient.PAGE_SIZE);
            final ArrayList<Contribution> items = new ArrayList<>(result.items);
            Log.d(TAG, "fetch 渲染 page=" + page + " 本页=" + items.size()
                    + " 总数=" + result.total + " 总页数=" + totalPages);
            // 记录本页原始条目：搜索条件变化时在此基础上重新过滤，无需重新请求（需求 5）
            lastPageItems = items;
            // 审核结果聚合：状态变化的已出结果条目写入未读收件箱
            AuditInbox.observe(MainActivity.this, items);
            // 顺带补充审核私信归档（限频）：歌词 / 翻译纠错只出现在私信里
            RejectArchive.syncIfNeeded(MainActivity.this);
            refreshUnreadState();
            updatePager(result.total);

            if (items.isEmpty()) {
                showEmpty();
            } else {
                // 先按当前搜索条件过滤并提交，再切到内容态
                submitFiltered();
                showContent();
                restoreContribScroll();
                resolvePendingFocusCard();
            }
        } catch (Exception e) {
            // 渲染阶段的任何异常都不得导致闪退
            Log.e(TAG, "fetch 渲染阶段异常，已降级为错误态", e);
            showError(messageFor(null), false);
        }
    }

    private void updatePager(int total) {
        // 单行完整展示：页码与总数合并为「1/100 · 3000条」，配合 autoSize 在窄屏自动降字号，
        // 避免旧版两行文案在 320dp 屏上被挤压或截断。
        String info = getString(R.string.contrib_page_info_full, currentPage, totalPages, total);
        boolean pageChanged = !info.contentEquals(tvPageInfo.getText());
        tvPageInfo.setText(info);
        tvPageInfo.setContentDescription(
                getString(R.string.contrib_page_info, currentPage, totalPages, total));
        if (pageChanged) {
            animatePagerUpdate();
        }
        // 首页 / 末页按钮与上一页 / 下一页共用同一套可用性判断（需求 4）
        setFiltersEnabled(currentStateTag != STATE_LOADING);
    }

    /** 页码变化的就位动效：从下方轻微上浮并淡入，与整页切换的节奏保持一致。 */
    private void animatePagerUpdate() {
        if (!MotionManager.animationsEnabled(this)) {
            return;
        }
        tvPageInfo.animate().cancel();
        tvPageInfo.setAlpha(0.35f);
        tvPageInfo.setTranslationY(px(4));
        tvPageInfo.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(MotionManager.controlDuration(this))
                .setInterpolator(MotionManager.emphasized(this))
                .start();
    }

    /**
     * 滚动状态恢复（需求 2）：把滚动位置还原到本次加载开始前的值。
     *
     * <p>先立即校正一次，再用 {@code postOnAnimation} 在本次布局完成后的下一帧再校正一次，
     * 以抵消列表内容高度变化（RecyclerView 换页后重新测量）带来的位置偏移。
     * 越界时 {@code scrollTo} 会自行钳制到合法范围，无需额外判断。
     */
    private void restoreContribScroll() {
        final int y = pendingScrollY;
        pendingScrollY = -1;
        if (contribScroll == null || y <= 0) {
            return;
        }
        contribScroll.scrollTo(0, y);
        contribScroll.postOnAnimation(() -> {
            if (contribScroll != null && !isFinishing() && !isDestroyed()) {
                contribScroll.scrollTo(0, y);
            }
        });
    }

    private void handleFailure(int gen, @Nullable WikiException error) {
        if (gen != generation || isFinishing() || isDestroyed()) {
            Log.d(TAG, "失败结果已被丢弃（过期代际或页面销毁）gen=" + gen);
            return;
        }
        inflight = null;

        if (error == null) {
            Log.e(TAG, "handleFailure：收到空的异常对象（存在未归类异常），展示网络错误提示");
        } else {
            Log.e(TAG, "handleFailure kind=" + error.getKind() + " httpCode=" + error.getHttpCode()
                    + " msg=" + error.getMessage(), error);
        }

        boolean credentialIssue = error != null
                && error.getKind() == WikiException.Kind.NO_CREDENTIAL;

        if (credentialIssue) {
            // 服务端判定登录态失效：清空本地凭据，引导重新登录，避免反复失败
            SessionManager.clear(this);
            statsGeneration++;
            cookieSnapshot = "";
            currentPage = 1;
            totalPages = 1;
            AuditPollingService.stop(this);
            clearListState();
            refreshAccountCard();
            showError(getString(R.string.contrib_error_not_logged_in), true);
            return;
        }

        clearListState();
        showError(messageFor(error), false);
    }

    /**
     * 列表被整体清空（未登录 / 请求失败）时同步清理派生状态：
     * 本页原始条目、检索命中集与扫描缓存都必须一并失效，否则重新登录后
     * 会先闪出上一账号的搜索结果行。
     */
    private void clearListState() {
        lastPageItems = Collections.emptyList();
        searching = false;
        searchMatches = Collections.emptyList();
        scanCache = null;
        if (adapter != null) {
            adapter.submitList(Collections.<Contribution>emptyList());
        }
        if (tvSearchResult != null) {
            tvSearchResult.setVisibility(View.GONE);
        }
    }

    /** 按异常类别给出差异化提示，避免所有失败都渲染成同一句话 */
    private String messageFor(@Nullable WikiException error) {
        if (error == null) {
            return getString(R.string.contrib_error_network);
        }
        switch (error.getKind()) {
            case TIMEOUT:
                return getString(R.string.wiki_timeout);
            case NO_NETWORK:
                return getString(R.string.wiki_no_network);
            case HTTP:
                return error.getHttpCode() > 0
                        ? getString(R.string.wiki_http_error, error.getHttpCode())
                        : getString(R.string.contrib_error_business);
            case TOO_LARGE:
                return getString(R.string.wiki_too_large);
            case BAD_JSON:
                return getString(R.string.wiki_bad_json);
            case EMPTY:
                return getString(R.string.contrib_error_business);
            case NO_CREDENTIAL:
                return getString(R.string.contrib_error_not_logged_in);
            default:
                return getString(R.string.contrib_error_network);
        }
    }

    private void cancelInflight() {
        if (inflight != null) {
            inflight.cancel();
            inflight = null;
        }
    }

    // ------------------------------------------------------------------
    // 四态切换
    // ------------------------------------------------------------------

    private void applyState(int state) {
        boolean hadContent = currentStateTag == STATE_CONTENT;
        currentStateTag = state;
        // 需求 2：已有内容时的「加载中」不清空、不隐藏列表区。
        // 否则滚动容器内容高度骤降到只剩顶部信息，系统会把 scrollY 钳回 0，
        // 表现为「点任意按钮页面都跳回顶部」。
        boolean keepContentVisible = hadContent && state == STATE_LOADING;
        boolean contentVisible = state == STATE_CONTENT || keepContentVisible;
        recyclerView.setVisibility(contentVisible ? View.VISIBLE : View.GONE);
        // 页面选择器统一走 setPagerVisible：由它维护 pagerShown 记账并播放上滑 / 下滑动画，
        // 直接 setVisibility 会打断正在播放的动画并让记账状态失步。
        setPagerVisible(contentVisible && destination == DEST_CONTRIBUTIONS);
        // 已有内容时不再叠加居中 loading 覆盖层：它会在旧列表上突然出现再消失，
        // 与 DiffUtil 更新叠加后被感知为「整页闪动」。此时保留下拉刷新指示器作为进度反馈。
        stateLoading.setVisibility(
                state == STATE_LOADING && !keepContentVisible ? View.VISIBLE : View.GONE);
        stateEmpty.setVisibility(state == STATE_EMPTY ? View.VISIBLE : View.GONE);
        stateError.setVisibility(state == STATE_ERROR ? View.VISIBLE : View.GONE);
        setFiltersEnabled(state != STATE_LOADING);
        // 只要本次请求有了确定结果（内容 / 空 / 错误）就收起下拉刷新的旋转指示器；
        // 处于加载中时保持旋转，让用户持续看到进度反馈（需求 2）。
        if (state != STATE_LOADING) {
            stopRefresh();
            // 换页结束（成功 / 空 / 失败）：恢复列表不透明度，收尾翻页过渡
            if (listDimmed) {
                listDimmed = false;
                MotionManager.undimList(this, recyclerView);
            }
        }
    }

    /** 加载中禁用筛选与分页，避免连点造成乱序请求 */
    private void setFiltersEnabled(boolean enabled) {
        tabLayout.setEnabled(enabled);
        chipStatus.setEnabled(enabled);
        groupTransport.setEnabled(enabled);
        boolean hasPrev = enabled && currentPage > 1;
        boolean hasNext = enabled && currentPage < totalPages;
        btnPagePrev.setEnabled(hasPrev);
        btnPageFirst.setEnabled(hasPrev);
        btnPageNext.setEnabled(hasNext);
        btnPageLast.setEnabled(hasNext);
        tvPageInfo.setClickable(enabled);
        // 页码文案不做加载态变暗：加载往返只有几百毫秒，变暗再恢复本身就是一次可见闪动。
        tvPageInfo.setAlpha(1f);
    }

    private void showLoading() {
        applyState(STATE_LOADING);
    }

    private void showContent() {
        applyState(STATE_CONTENT);
    }

    private void showEmpty() {
        // 搜索条件生效时，空态文案换成「没有匹配的贡献」，避免误以为账号本身没有贡献（需求 5）
        boolean searching = isSearchActive();
        emptyTitle.setText(searching
                ? R.string.contrib_search_empty_title : R.string.contrib_empty_title);
        emptyDesc.setText(searching
                ? R.string.contrib_search_empty_desc : R.string.contrib_empty_desc);
        applyState(STATE_EMPTY);
    }

    private void showError(String message, boolean goLogin) {
        errorMessage.setText(message);
        errorActionGoLogin = goLogin;
        errorAction.setText(goLogin ? R.string.contrib_go_login : R.string.wiki_error_retry);
        applyState(STATE_ERROR);
    }

    // ------------------------------------------------------------------
    // 超限动画 → 模糊跟随
    // ------------------------------------------------------------------

    /**
     * 下拉 / 回弹时让模糊跟随内容的视觉位移。
     *
     * <p>这里<b>只平移已采样的模糊位图</b>（{@link BackdropBlurView#setDrawOffsetY}），
     * 不触发重采样，因此不会与原生超限动画抢主线程帧预算；页面主体的滚动、回弹仍完全
     * 交给系统与 SwipeRefreshLayout，行为与「关闭模糊」时保持一致。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        handleOverscrollGesture(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** 当前页面用于检测超限回弹的滚动容器（贡献 / 百科 / 下载 / 上传）。 */
    @Nullable
    private NestedScrollView activeScroll() {
        switch (destination) {
            case DEST_CONTRIBUTIONS:
                return contribScroll;
            case DEST_WIKI:
                return wikiView == null ? null : wikiView.mainScroll();
            case DEST_DOWNLOAD:
                return downloadView == null ? null : downloadView.mainScroll();
            case DEST_ALBUM_UPLOAD:
                return albumUploadView == null ? null : albumUploadView.mainScroll();
            default:
                return null;
        }
    }

    private void handleOverscrollGesture(MotionEvent ev) {
        if (overlayBlur == null || overlayBlur.getGlassStyle() == BackdropBlurView.STYLE_NONE) {
            return;
        }
        final NestedScrollView scroll = activeScroll();
        if (scroll == null) {
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownY = ev.getRawY();
                scrollAtTopOnDown = scroll.getScrollY() <= 0;
                if (scrollAtTopOnDown && overscrollAnimator != null) {
                    overscrollAnimator.cancel();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (scrollAtTopOnDown && scroll.getScrollY() <= 0) {
                    float drag = ev.getRawY() - touchDownY;
                    // 阻尼 0.5，并限制在采样外扩量以内（绘制时还会再做一次边界钳制）
                    float limit = px(28);
                    overlayBlur.setDrawOffsetY(Math.min(limit, Math.max(0f, drag)) * 0.5f);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (scrollAtTopOnDown) {
                    settleOverscroll();
                }
                scrollAtTopOnDown = false;
                break;
            default:
                break;
        }
    }

    /** 松手后把模糊位移平滑回位（与原生回弹同节奏） */
    private void settleOverscroll() {
        if (overlayBlur == null) {
            return;
        }
        float from = overlayBlur.getDrawOffsetY();
        if (Math.abs(from) < 0.5f) {
            overlayBlur.setDrawOffsetY(0f);
            return;
        }
        if (overscrollAnimator != null) {
            overscrollAnimator.cancel();
        }
        overscrollAnimator = ValueAnimator.ofFloat(from, 0f);
        overscrollAnimator.setDuration(260L);
        overscrollAnimator.setInterpolator(MotionManager.standard(this));
        overscrollAnimator.addUpdateListener(a -> {
            if (overlayBlur != null) {
                overlayBlur.setDrawOffsetY((float) a.getAnimatedValue());
            }
        });
        overscrollAnimator.start();
    }

    /** 审核消息变化：角标与未读置顶实时刷新（无需重新进入页面） */
    private final AuditInbox.Listener auditInboxListener = () -> {
        refreshUnreadState();
        if (destination == DEST_CONTRIBUTIONS && currentStateTag == STATE_CONTENT
                && lastPageItems != null) {
            submitFiltered();
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && bottomOverlay != null) {
            // 键盘动画被窗口切换打断后位移可能停在半途，回到本页时兜底纠正一次
            bottomOverlay.post(this::syncOverlayImeOffset);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        AuditInbox.removeListener(auditInboxListener);
        // 键盘位移可能因窗口切换停在半途：离开时先归位，
        // 回来由下一次 insets 下发按真实状态重新应用，避免底栏卡在页面中部
        resetOverlayImeOffset();
        // 收尾未完成的翻页过渡，避免返回时列表仍停在压暗态
        listDimmed = false;
        pageNavPending = false;
        scrollAtTopOnDown = false;
        if (recyclerView != null) {
            recyclerView.animate().cancel();
            recyclerView.setAlpha(1f);
        }
        if (overscrollAnimator != null) {
            overscrollAnimator.cancel();
            overscrollAnimator = null;
        }
        if (overlayBlur != null) {
            overlayBlur.setDrawOffsetY(0f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 卡片显隐先于其它刷新应用：从「设置-显示」返回时立刻看到结果
        applyCardVisibility();
        // 导航栏样式在「设置-外观」中可能被改动，回到主界面时幂等重排即可生效
        applyNavStyle();
        // 搜索卡片布局可在「设置-显示」中切换，返回主界面时重排
        applySearchLayout();
        // 未读变化实时刷新角标与列表
        AuditInbox.addListener(auditInboxListener);
        // 登录 / 授权返回后也要确保后台轮询在跑：只依赖 onCreate 会漏掉「先启动后登录」的场景
        AuditPollingService.startIfEnabled(this);
        AuditPollingService.checkNow(this);
        // 登录 / 退出登录会改变凭据；设置里改过封面开关也需要重新应用，
        // 两者任一变化都重新拉取，避免列表停留在旧状态。
        if (destination == DEST_CONTRIBUTIONS) {
            String current = SessionManager.cookie(this);
            boolean coversOn = WikiPrefs.showContributionCovers(this);
            if (currentStateTag == STATE_IDLE || !current.equals(cookieSnapshot)
                    || coversOn != coversSettingSnapshot) {
                reload();
            } else {
                refreshAccountCard();
            }
        }
        // 后台轮询可能新增了未读审核结果：回到前台立即刷新角标与「新」标记
        refreshUnreadState();
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AuditPollingService.startIfEnabled(this);
        }
    }
}
