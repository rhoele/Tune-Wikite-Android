package com.auramusic.wiki.classic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 声云资源下载页（控制器）。
 *
 * <p>把 scimg 的封面策略与 lucida 的音源链路收在一个页面里：
 * 解析元数据 → 抓母带封面 → 下载所选格式 → 可选 32 bit WAV → 传入专辑上传。
 */
public class SoundCloudDownloadView extends FrameLayout {

    public interface HandoffListener {
        void onTransferToAlbum();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private NestedScrollView scroll;
    private EditText urlInput;
    private ImageView coverPreview;
    private TextView titleView;
    private TextView artistView;
    private TextView durationView;
    private TextView coverSourceView;
    private TextView statusView;
    private MaterialAutoCompleteTextView formatInput;
    private MaterialSwitch convertSwitch;
    private MaterialButton coverButton;
    private MaterialButton audioButton;
    private MaterialButton handoffButton;
    private MaterialCheckBox handoffCover;
    private MaterialCheckBox handoffAudio;
    private MaterialCheckBox handoffSource;
    private MaterialButton retryButton;
    private MaterialButton stopButton;
    private LinearLayout failureList;
    private LinearProgressIndicator progress;
    private Runnable retryAction;
    /** 用户请求停止当前下载：批量任务会在当前曲目结束后退出 */
    private volatile boolean stopRequested;
    /** 立即取消信号：交给下载通道，让正在读响应体的任务尽快退出 */
    private final java.util.concurrent.atomic.AtomicBoolean cancelRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 下载任务代次：迟到的旧回调不得覆盖新任务的状态与按钮 */
    private int taskSeq;
    /** 最近一次批量下载失败的曲目（供逐首重下） */
    private final List<SoundCloudSetClient.Track> failedTracks = new ArrayList<>();

    private SoundCloudApiClient.Track track;
    /** set（播放列表）解析结果；非空时下载与传入都按曲序处理多首。 */
    private SoundCloudSetClient.Set set;
    /** 当前链接已解析出的下载结果（单曲 1 条，set 多条，顺序即曲序）。 */
    private final List<DownloadedTrack> downloadedTracks = new ArrayList<>();
    private File coverFile;
    private String selectedFormat = LucidaClient.FORMATS[0];
    private HandoffListener handoffListener;
    @Nullable
    private Bitmap previewBitmap;
    private boolean busy;
    /** 上次成功解析的链接：换链接时清空上一批下载结果 */
    private String resolvedUrl = "";

    /** 已下载的一首音频，保留标题用于交接给上传页。 */
    private static final class DownloadedTrack {
        final File file;
        final String title;
        final String artist;

        DownloadedTrack(File file, String title, String artist) {
            this.file = file;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }
    }

    public SoundCloudDownloadView(Context context) {
        super(context);
        init(context);
    }

    public SoundCloudDownloadView(Context context, @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SoundCloudDownloadView(Context context, @Nullable android.util.AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_soundcloud, this, true);
        scroll = findViewById(R.id.sc_scroll);
        urlInput = findViewById(R.id.et_sc_url);
        InputScroll.enable(urlInput);
        installFocusClearing();
        coverPreview = findViewById(R.id.iv_sc_cover);
        titleView = findViewById(R.id.tv_sc_title);
        artistView = findViewById(R.id.tv_sc_artist);
        durationView = findViewById(R.id.tv_sc_duration);
        coverSourceView = findViewById(R.id.tv_sc_cover_source);
        statusView = findViewById(R.id.tv_sc_status);
        formatInput = findViewById(R.id.act_sc_format);
        convertSwitch = findViewById(R.id.switch_sc_convert);
        coverButton = findViewById(R.id.btn_sc_cover);
        audioButton = findViewById(R.id.btn_sc_audio);
        handoffButton = findViewById(R.id.btn_sc_handoff);
        handoffCover = findViewById(R.id.cb_sc_handoff_cover);
        handoffAudio = findViewById(R.id.cb_sc_handoff_audio);
        handoffSource = findViewById(R.id.cb_sc_handoff_source);
        retryButton = findViewById(R.id.btn_sc_retry);
        stopButton = findViewById(R.id.btn_sc_stop);
        failureList = findViewById(R.id.ll_sc_failures);
        progress = findViewById(R.id.pb_sc);

        String[] labels = new String[LucidaClient.FORMATS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = LucidaClient.formatLabel(LucidaClient.FORMATS[i]);
        }
        // 候选固定为全量：避免状态恢复时被 AutoCompleteTextView 按当前值过滤
        formatInput.setAdapter(new FixedItemsAdapter(context, labels));
        formatInput.setThreshold(0);
        formatInput.setText(labels[0], false);
        formatInput.setOnItemClickListener((parent, view, position, id) ->
                selectedFormat = LucidaClient.FORMATS[position]);

        findViewById(R.id.btn_sc_paste).setOnClickListener(v -> {
            HapticsManager.tap(v);
            pasteFromClipboard();
        });
        findViewById(R.id.btn_sc_resolve).setOnClickListener(v -> {
            HapticsManager.tap(v);
            resolveMetadata();
        });
        coverButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            downloadCover();
        });
        audioButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            downloadAudio();
        });
        handoffButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            transferToAlbum();
        });
        handoffCover.setOnCheckedChangeListener((buttonView, isChecked) -> updateHandoff());
        handoffAudio.setOnCheckedChangeListener((buttonView, isChecked) -> updateHandoff());
        handoffSource.setOnCheckedChangeListener((buttonView, isChecked) -> updateHandoff());
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateHandoff();
            }
        });
        retryButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            Runnable action = retryAction;
            hideRetry();
            if (action != null) {
                action.run();
            }
        });
        stopButton.setOnClickListener(v -> {
            HapticsManager.tap(v);
            requestStop();
        });
        updateHandoff();
    }

    public void setHandoffListener(HandoffListener listener) {
        this.handoffListener = listener;
    }

    /** 页面主滚动容器（供底栏模糊跟随超限位移）。 */
    androidx.core.widget.NestedScrollView mainScroll() {
        return scroll;
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

    /** 底部覆盖层高度（px）：只增加滚动余量，不裁剪内容。 */
    public void setBottomInset(int px) {
        if (scroll == null) {
            return;
        }
        int left = scroll.getPaddingLeft();
        int top = scroll.getPaddingTop();
        int right = scroll.getPaddingRight();
        if (scroll.getPaddingBottom() != px) {
            scroll.setPadding(left, top, right, px);
        }
    }

    /**
     * 点击页面空白处时收起输入法并取消焦点。
     *
     * <p>只处理落在当前焦点输入框之外的按下事件：点输入框继续编辑、点按钮或下拉框
     * 则交给控件自己处理，不拦截点击与波纹。
     */
    private void installFocusClearing() {
        if (scroll == null) {
            return;
        }
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                View focused = findFocus();
                if (focused instanceof EditText && !hitInside(focused, event)) {
                    focused.clearFocus();
                    hideKeyboard();
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

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(getContext());
        if (text != null && text.length() > 0) {
            urlInput.setText(SoundCloudCoverClient.extractUrl(text.toString()));
            urlInput.setSelection(urlInput.getText().length());
        }
    }

    private String currentUrl() {
        CharSequence text = urlInput.getText();
        return SoundCloudCoverClient.extractUrl(text == null ? "" : text.toString());
    }

    private void resolveMetadata() {
        final String url = currentUrl();
        if (TextUtils.isEmpty(url)) {
            toast(getContext().getString(R.string.sc_error_no_url));
            return;
        }
        setBusy(true, getContext().getString(R.string.sc_status_idle));
        executor.execute(() -> {
            try {
                // set 链接先按播放列表解析；拿不到曲目清单时回退单曲解析
                if (SoundCloudSetClient.looksLikeSet(url)) {
                    SoundCloudSetClient.Set parsed = null;
                    try {
                        parsed = SoundCloudSetClient.fetch(url);
                    } catch (Exception ignored) {
                        // 短链失效或页面被风控，交给 lucida 的单曲通道兜底
                    }
                    if (parsed != null) {
                        final SoundCloudSetClient.Set result = parsed;
                        main.post(() -> {
                            resetDownloadsIfUrlChanged(url);
                            set = result;
                            track = null;
                            showSet(result);
                            hideRetry();
                            setBusy(false, "已解析：" + result.title
                                    + " · " + result.totalCount + " 首"
                                    + (result.unavailableCount > 0
                                    ? "（" + result.unavailableCount + " 首不可用）" : ""));
                        });
                        return;
                    }
                    // 明确的 set 链接解析失败时不要回退成单曲：
                    // lucida 只会报「Cannot stream this item type: album」，反而误导用户
                    if (SoundCloudSetClient.looksLikeSet(url)) {
                        throw new Exception(getContext().getString(R.string.sc_set_parse_failed));
                    }
                }
                SoundCloudApiClient.Track resolved = SoundCloudApiClient.resolve(url);
                main.post(() -> {
                    resetDownloadsIfUrlChanged(url);
                    set = null;
                    track = resolved;
                    showTrack(resolved);
                    hideRetry();
                    setBusy(false, "已解析：" + resolved.title);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false, getContext().getString(R.string.sc_resolve_failed,
                            messageOf(e)));
                    showRetry(this::resolveMetadata);
                });
            }
        });
    }

    /** 换链接后清掉上一批下载结果，避免把旧文件传给新专辑。 */
    private void resetDownloadsIfUrlChanged(String url) {
        if (url.equals(resolvedUrl)) {
            return;
        }
        resolvedUrl = url;
        downloadedTracks.clear();
        coverFile = null;
        failedTracks.clear();
        renderFailures();
        audioButton.setText(R.string.sc_action_download_audio);
        updateHandoff();
    }

    private void showTrack(SoundCloudApiClient.Track t) {
        audioButton.setText(R.string.sc_action_download_audio);
        titleView.setText(TextUtils.isEmpty(t.title) ? t.url : t.title);
        artistView.setVisibility(TextUtils.isEmpty(t.artist) ? View.GONE : View.VISIBLE);
        artistView.setText(t.artist);
        durationView.setVisibility(t.durationMs > 0 ? View.VISIBLE : View.GONE);
        durationView.setText(formatDuration(t.durationMs));
        MotionManager.fadeSlideIn(getContext(), titleView);
        if (!TextUtils.isEmpty(t.artist)) {
            MotionManager.fadeSlideIn(getContext(), artistView);
        }
        if (t.durationMs > 0) {
            MotionManager.fadeSlideIn(getContext(), durationView);
        }
        if (!TextUtils.isEmpty(t.coverUrl)) {
            String original = t.coverUrl.replace("-large.", "-original.")
                    .replace("-t500x500.", "-original.")
                    .replace("-t1080x1080.", "-original.");
            loadPreview(original);
        }
    }

    /** 展示 set（播放列表）信息：曲目数、总时长，并把音频按钮改为批量下载。 */
    private void showSet(SoundCloudSetClient.Set s) {
        int downloadable = Math.max(0, s.tracks.size() - s.previewCount);
        audioButton.setText(downloadable == 0
                ? getContext().getString(R.string.sc_action_preview_only)
                : getContext().getString(R.string.sc_action_download_all, downloadable));
        titleView.setText(TextUtils.isEmpty(s.title) ? s.url : s.title);
        String count = getContext().getString(R.string.sc_set_count, s.totalCount);
        String meta = TextUtils.isEmpty(s.artist) ? count : s.artist + " · " + count;
        if (s.unavailableCount > 0) {
            meta += " · " + getContext().getString(
                    R.string.sc_set_unavailable, s.unavailableCount);
        }
        if (s.previewCount > 0) {
            meta += " · " + getContext().getString(
                    R.string.sc_set_preview_only, s.previewCount);
        }
        artistView.setVisibility(View.VISIBLE);
        artistView.setText(meta);
        long total = s.totalDurationMs();
        durationView.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        durationView.setText(formatDuration(total));
        MotionManager.fadeSlideIn(getContext(), titleView);
        MotionManager.fadeSlideIn(getContext(), artistView);
        if (total > 0) {
            MotionManager.fadeSlideIn(getContext(), durationView);
        }
        if (!TextUtils.isEmpty(s.artworkUrl)) {
            String original = s.artworkUrl.replace("-large.", "-original.")
                    .replace("-t500x500.", "-original.")
                    .replace("-t1080x1080.", "-original.");
            loadPreview(original);
        }
    }

    private void loadPreview(String url) {
        executor.execute(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        main.post(() -> {
                            if (previewBitmap != null) {
                                previewBitmap.recycle();
                            }
                            previewBitmap = bmp;
                            coverPreview.setPadding(0, 0, 0, 0);
                            coverPreview.setImageBitmap(bmp);
                            MotionManager.fadeInImage(getContext(), coverPreview);
                        });
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
                // 预览失败不影响后续下载
            }
        });
    }

    private void downloadCover() {
        final String url = currentUrl();
        if (TextUtils.isEmpty(url)) {
            toast(getContext().getString(R.string.sc_error_no_url));
            return;
        }
        final String fallback = track != null ? track.coverUrl
                : (set == null ? "" : set.artworkUrl);
        final String fallbackTitle = track != null ? track.title
                : (set == null ? "" : set.title);
        setBusy(true, getContext().getString(R.string.sc_progress_cover));
        prepareNewTask();
        coverSourceView.setText(R.string.sc_progress_cover);
        executor.execute(() -> {
            try {
                File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_COVER);
                SoundCloudCoverClient.Result result =
                        SoundCloudCoverClient.fetch(url, fallback, dir, fallbackTitle);
                DownloadStore.exportAsync(getContext(), DownloadStore.KIND_COVER, result.file);
                main.post(() -> {
                    coverFile = result.file;
                    coverSourceView.setText(getContext().getString(R.string.sc_status_cover_ok,
                            result.file.getName()) + " · " + result.width + "×" + result.height
                            + (result.isOriginal() ? " · original" : " · " + result.variant));
                    loadPreviewFromFile(result.file);
                    hideRetry();
                    setBusy(false, getContext().getString(R.string.sc_status_cover_ok,
                            result.file.getName()));
                    markHandoffAvailable(false, true);
                });
            } catch (Exception e) {
                main.post(() -> {
                    coverSourceView.setText(R.string.sc_no_cover);
                    setBusy(false, getContext().getString(R.string.sc_cover_failed,
                            messageOf(e)));
                    showRetry(this::downloadCover);
                });
            }
        });
    }

    private void loadPreviewFromFile(File file) {
        executor.execute(() -> {
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bmp != null) {
                main.post(() -> {
                    if (previewBitmap != null) {
                        previewBitmap.recycle();
                    }
                    previewBitmap = bmp;
                    coverPreview.setPadding(0, 0, 0, 0);
                    coverPreview.setImageBitmap(bmp);
                    MotionManager.fadeInImage(getContext(), coverPreview);
                });
            }
        });
    }

    private void downloadAudio() {
        final String url = currentUrl();
        if (TextUtils.isEmpty(url)) {
            toast(getContext().getString(R.string.sc_error_no_url));
            return;
        }
        // set：逐首按曲序下载
        if (set != null) {
            downloadSetAudio();
            return;
        }
        final boolean convert = convertSwitch.isChecked();
        // 转换 WAV 需要可解码的容器，统一让 lucida 产出 m4a-aac-320
        final String lucidaFormat = convert ? "m4a-aac-320" : selectedFormat;
        final String title = titleOf(track == null ? null : track.title, url);
        final String artist = track == null ? "" : track.artist;
        final AudioTagger.Tags tags = tagsFor(title, artist, track, null, 0);
        setBusy(true, getContext().getString(R.string.sc_progress_audio, 0));
        prepareNewTask();
        final int task = ++taskSeq;
        // 每次单曲下载重新询问「是否允许直连兜底」
        SoundCloudDownloader.resetPrompt();
        executor.execute(() -> {
            try {
                File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_AUDIO);
                File downloaded = SoundCloudDownloader.download(activity(), url, lucidaFormat, dir,
                        title, tags,
                        new SoundCloudDownloader.Progress() {
                            @Override
                            public void onStatus(String text) {
                                main.post(() -> {
                                    if (task != taskSeq) {
                                        return;
                                    }
                                    statusView.setText(text);
                                });
                            }

                            @Override
                            public void onPercent(int percent) {
                                main.post(() -> {
                                    if (task != taskSeq) {
                                        return;
                                    }
                                    statusView.setText(getContext().getString(
                                            R.string.sc_progress_audio, percent));
                                });
                            }
                        }, cancelRequested);
                File result = downloaded;
                if (convert) {
                    main.post(() -> {
                        if (task == taskSeq) {
                            statusView.setText(R.string.sc_progress_convert);
                        }
                    });
                    File wav = new File(downloaded.getParentFile(),
                            stripExt(downloaded.getName()) + "-32bit.wav");
                    result = PcmWavConverter.toWav32(downloaded, wav,
                            text -> main.post(() -> {
                                if (task == taskSeq) {
                                    statusView.setText(text);
                                }
                            }));
                }
                final File finalFile = result;
                DownloadStore.exportAsync(getContext(), DownloadStore.KIND_AUDIO, finalFile);
                main.post(() -> {
                    if (task != taskSeq) {
                        return;
                    }
                    downloadedTracks.clear();
                    downloadedTracks.add(new DownloadedTrack(finalFile, title, artist));
                    hideRetry();
                    setBusy(false, getContext().getString(convert
                            ? R.string.sc_status_convert_ok : R.string.sc_status_audio_ok,
                            finalFile.getName()));
                    markHandoffAvailable(true, false);
                });
            } catch (Exception e) {
                final boolean cancelled = cancelRequested.get();
                main.post(() -> {
                    if (task != taskSeq) {
                        return;
                    }
                    if (cancelled) {
                        // 用户主动停止：明确告知已结束，并恢复可操作状态
                        setBusy(false, getContext().getString(R.string.sc_status_stopped));
                        hideRetry();
                        return;
                    }
                    setBusy(false, getContext().getString(R.string.sc_audio_failed,
                            messageOf(e)));
                    showRetry(this::downloadAudio);
                });
            }
        });
    }

    /** 批量下载 set 里的全部曲目；单首失败不中断，最后汇总失败清单并支持逐首重下。 */
    private void downloadSetAudio() {
        final SoundCloudSetClient.Set current = set;
        if (current == null || current.tracks.isEmpty()) {
            toast(getContext().getString(R.string.sc_set_empty));
            return;
        }
        final boolean convert = convertSwitch.isChecked();
        final String lucidaFormat = convert ? "m4a-aac-320" : selectedFormat;
        final int total = current.tracks.size();
        final int downloadable = Math.max(0, total - current.previewCount);
        if (downloadable == 0) {
            // 全部是 SNIP：SoundCloud 只下发试听片段，提前给出可读的原因
            toast(getContext().getString(R.string.sc_status_set_all_preview, current.previewCount));
            return;
        }
        if (current.unavailableCount > 0 || current.previewCount > 0) {
            toast(getContext().getString(R.string.sc_status_set_filtered,
                    total, current.unavailableCount, current.previewCount, downloadable));
        }
        prepareNewTask();
        // 每批 set 只询问一次「是否允许直连兜底」
        SoundCloudDownloader.resetPrompt();
        setBusy(true, getContext().getString(R.string.sc_progress_set, 0, downloadable, 0));
        final int task = ++taskSeq;
        executor.execute(() -> {
            File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_AUDIO);
            List<DownloadedTrack> out = new ArrayList<>();
            List<SoundCloudSetClient.Track> failed = new ArrayList<>();
            int skippedPreview = 0;
            int processed = 0;
            boolean stopped = false;
            for (int i = 0; i < total; i++) {
                if (stopRequested || cancelRequested.get()) {
                    stopped = true;
                    break;
                }
                final SoundCloudSetClient.Track item = current.tracks.get(i);
                if (item.previewOnly) {
                    // 仅试听曲目没有完整音源，直接跳过，避免计入下载失败
                    skippedPreview++;
                    continue;
                }
                final int index = ++processed;
                final String trackTitle = titleOf(item.title, item.url);
                // 实时显示当前正在下载的曲目名称
                main.post(() -> {
                    if (task != taskSeq) {
                        return;
                    }
                    statusView.setText(getContext().getString(
                            R.string.sc_progress_set_current, trackTitle, index, downloadable));
                });
                try {
                    // 前缀序号既保证文件顺序，也避免同名曲目互相覆盖
                    String fileName = String.format(Locale.CHINA, "%02d - %s", index, trackTitle);
                    AudioTagger.Tags tags = tagsFor(trackTitle, item.artist, null, current, index);
                    File downloaded = SoundCloudDownloader.download(activity(), item.url,
                            lucidaFormat, dir, fileName, tags,
                            new SoundCloudDownloader.Progress() {
                                @Override
                                public void onStatus(String text) {
                                    main.post(() -> {
                                        if (task != taskSeq) {
                                            return;
                                        }
                                        statusView.setText(getContext().getString(
                                                R.string.sc_progress_set_current,
                                                trackTitle, index, downloadable));
                                    });
                                }

                                @Override
                                public void onPercent(int percent) {
                                    main.post(() -> {
                                        if (task != taskSeq) {
                                            return;
                                        }
                                        statusView.setText(
                                                getContext().getString(R.string.sc_progress_set_current,
                                                        trackTitle, index, downloadable)
                                                        + " · " + percent + "%");
                                    });
                                }
                            }, cancelRequested);
                    File result = downloaded;
                    if (convert) {
                        File wav = new File(downloaded.getParentFile(),
                                stripExt(downloaded.getName()) + "-32bit.wav");
                        result = PcmWavConverter.toWav32(downloaded, wav, null);
                    }
                    out.add(new DownloadedTrack(result, item.title, item.artist));
                    DownloadStore.exportAsync(getContext(), DownloadStore.KIND_AUDIO, result);
                } catch (Exception e) {
                    failed.add(item);
                }
            }
            final int failedCount = failed.size();
            final int previewSkipped = skippedPreview;
            final int processedCount = processed;
            final boolean wasStopped = stopped;
            main.post(() -> {
                if (task != taskSeq) {
                    return;
                }
                downloadedTracks.clear();
                downloadedTracks.addAll(out);
                failedTracks.clear();
                failedTracks.addAll(failed);
                renderFailures();
                hideRetry();
                if (wasStopped) {
                    setBusy(false, getContext().getString(R.string.sc_status_stopped_partial,
                            out.size(), Math.max(0, downloadable - processedCount)));
                    markHandoffAvailable(!out.isEmpty(), false);
                    return;
                }
                if (out.isEmpty()) {
                    if (previewSkipped > 0) {
                        setBusy(false, getContext().getString(
                                R.string.sc_status_set_all_preview, previewSkipped));
                    } else {
                        setBusy(false, getContext().getString(R.string.sc_audio_failed, ""));
                        // 整批都失败：重试应该重下整批，而不是退化成单曲下载
                        showRetry(this::downloadSetAudio);
                    }
                    return;
                }
                String status;
                if (failedCount == 0 && previewSkipped == 0) {
                    status = getContext().getString(R.string.sc_status_set_ok, out.size());
                } else if (failedCount == 0) {
                    status = getContext().getString(R.string.sc_status_set_preview_partial,
                            out.size(), previewSkipped);
                } else {
                    status = getContext().getString(R.string.sc_status_set_partial,
                            out.size(), failedCount + previewSkipped);
                }
                setBusy(false, status);
                markHandoffAvailable(true, false);
            });
        });
    }

    private void transferToAlbum() {
        boolean hasAudio = !downloadedTracks.isEmpty();
        boolean hasCover = coverFile != null && coverFile.isFile();
        boolean hasSource = !TextUtils.isEmpty(currentUrl());
        boolean wantAudio = handoffAudio.isChecked() && hasAudio;
        boolean wantCover = handoffCover.isChecked() && hasCover;
        boolean wantSource = handoffSource.isChecked() && hasSource;
        if (!wantAudio && !wantCover && !wantSource) {
            toast(getContext().getString(R.string.sc_handoff_none));
            return;
        }
        List<TransferStash.Track> tracks = new ArrayList<>();
        if (wantAudio) {
            // 顺序即曲序：上传页按该顺序追加并重新编号
            for (DownloadedTrack item : downloadedTracks) {
                tracks.add(new TransferStash.Track(item.file, item.title, item.artist));
            }
        }
        TransferStash.put(tracks, wantCover ? coverFile : null,
                wantSource ? currentUrl() : "");
        if (handoffListener != null) {
            handoffListener.onTransferToAlbum();
        }
    }

    /** 勾选项与已下载文件联动：内容不存在时禁用并取消勾选。 */
    private void updateHandoff() {
        boolean hasAudio = !downloadedTracks.isEmpty();
        boolean hasCover = coverFile != null && coverFile.isFile();
        boolean hasSource = !TextUtils.isEmpty(currentUrl());
        handoffAudio.setEnabled(hasAudio && !busy);
        handoffCover.setEnabled(hasCover && !busy);
        handoffSource.setEnabled(hasSource && !busy);
        if (!hasAudio) {
            handoffAudio.setChecked(false);
        }
        if (!hasCover) {
            handoffCover.setChecked(false);
        }
        if (!hasSource) {
            handoffSource.setChecked(false);
        }
        boolean canTransfer = (hasAudio && handoffAudio.isChecked())
                || (hasCover && handoffCover.isChecked())
                || (hasSource && handoffSource.isChecked());
        handoffButton.setEnabled(!busy && canTransfer);
    }

    /** 新文件下载完成后恢复默认勾选，保证「下载完即可传入」。 */
    private void markHandoffAvailable(boolean audio, boolean cover) {
        if (audio) {
            handoffAudio.setChecked(true);
        }
        if (cover) {
            handoffCover.setChecked(true);
        }
        updateHandoff();
    }

    private void setBusy(boolean value, String status) {
        busy = value;
        // 进度条始终占位（INVISIBLE 而非 GONE）：开始 / 结束下载时页面不再上下跳动，
        // 淡入淡出由进度指示器自身的 show / hide 动画完成。
        if (value) {
            progress.show();
        } else {
            progress.hide();
        }
        coverButton.setEnabled(!value);
        audioButton.setEnabled(!value);
        formatInput.setEnabled(!value);
        convertSwitch.setEnabled(!value);
        if (value) {
            // 下载中只给「停止」：以前要等失败后才出现，用户看到卡在某个百分比时无从操作
            retryAction = null;
            MotionManager.animateVisibility(getContext(), retryButton, false);
            MotionManager.animateVisibility(getContext(), stopButton, true);
        } else {
            MotionManager.animateVisibility(getContext(), stopButton, false);
        }
        if (!TextUtils.isEmpty(status)) {
            statusView.setText(status);
        }
        updateHandoff();
    }

    /** 出错时同时给出「停止」「重试」两个选择（任务已中断，停止仅收起提示并复位状态）。 */
    private void showRetry(Runnable action) {
        retryAction = action;
        MotionManager.animateVisibility(getContext(), retryButton, true);
        MotionManager.animateVisibility(getContext(), stopButton, true);
    }

    private void hideRetry() {
        retryAction = null;
        MotionManager.animateVisibility(getContext(), retryButton, false);
        MotionManager.animateVisibility(getContext(), stopButton, false);
    }

    /** 用户点按「停止」：立刻取消当前通道，批量任务在当前曲目结束处退出。 */
    private void requestStop() {
        stopRequested = true;
        cancelRequested.set(true);
        hideRetry();
        statusView.setText(R.string.sc_status_stopping);
    }

    /** 点击「重试」/开始新一轮任务时复位停止标记与失败清单。 */
    private void prepareNewTask() {
        stopRequested = false;
        cancelRequested.set(false);
        failedTracks.clear();
        renderFailures();
    }

    /**
     * 页面被移出窗口（切换底部导航 / Activity 销毁）：取消下载并关掉浏览器兜底通道，
     * 避免 WebView + Dialog 一直挂在窗口上直到超时。
     */
    @Override
    protected void onDetachedFromWindow() {
        cancelRequested.set(true);
        stopRequested = true;
        LucidaBrowser.closeAll();
        super.onDetachedFromWindow();
    }

    /** 渲染批量下载的失败清单：每行一首歌 + 单独重下按钮。 */
    private void renderFailures() {
        if (failureList == null) {
            return;
        }
        failureList.removeAllViews();
        if (failedTracks.isEmpty()) {
            failureList.setVisibility(View.GONE);
            return;
        }
        failureList.setVisibility(View.VISIBLE);
        TextView title = new TextView(getContext());
        title.setText(R.string.sc_failures_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        failureList.addView(title);
        for (final SoundCloudSetClient.Track item : new ArrayList<>(failedTracks)) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView name = new TextView(getContext());
            name.setText(TextUtils.isEmpty(item.title) ? item.url : item.title);
            name.setTextAppearance(R.style.TextAppearance_ClassicWiki_Supporting);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            MaterialButton retry = new MaterialButton(getContext());
            retry.setText(R.string.sc_failure_retry);
            retry.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    retry, androidx.appcompat.R.attr.colorPrimary));
            retry.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            retry.setMinHeight(dp(48));
            retry.setOnClickListener(v -> {
                HapticsManager.tap(v);
                retryFailedTrack(item);
            });
            row.addView(retry);
            failureList.addView(row);
        }
    }

    /** 单首重下：成功后从失败清单移除并加入可交接列表。 */
    private void retryFailedTrack(final SoundCloudSetClient.Track item) {
        if (busy || item == null) {
            return;
        }
        final boolean convert = convertSwitch.isChecked();
        final String lucidaFormat = convert ? "m4a-aac-320" : selectedFormat;
        final String title = titleOf(item.title, item.url);
        // 重下时仍按该曲目在合集里的位置写音轨号
        final int trackNo = set == null ? 0 : set.tracks.indexOf(item) + 1;
        setBusy(true, getContext().getString(R.string.sc_progress_set_current, title, 1, 1));
        executor.execute(() -> {
            try {
                File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_AUDIO);
                File downloaded = SoundCloudDownloader.download(activity(), item.url, lucidaFormat,
                        dir, title, tagsFor(title, item.artist, null, set, trackNo),
                        new SoundCloudDownloader.Progress() {
                            @Override
                            public void onStatus(String text) {
                                main.post(() -> statusView.setText(getContext().getString(
                                        R.string.sc_progress_set_current, title, 1, 1)));
                            }

                            @Override
                            public void onPercent(int percent) {
                                main.post(() -> statusView.setText(getContext().getString(
                                        R.string.sc_progress_set_current, title, 1, 1)
                                        + " · " + percent + "%"));
                            }
                        });
                File result = downloaded;
                if (convert) {
                    File wav = new File(downloaded.getParentFile(),
                            stripExt(downloaded.getName()) + "-32bit.wav");
                    result = PcmWavConverter.toWav32(downloaded, wav, null);
                }
                final File finalFile = result;
                DownloadStore.exportAsync(getContext(), DownloadStore.KIND_AUDIO, finalFile);
                main.post(() -> {
                    downloadedTracks.add(new DownloadedTrack(finalFile, item.title, item.artist));
                    failedTracks.remove(item);
                    renderFailures();
                    hideRetry();
                    setBusy(false, getContext().getString(
                            R.string.sc_status_audio_ok, finalFile.getName()));
                    markHandoffAvailable(true, false);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false, getContext().getString(R.string.sc_audio_failed,
                            messageOf(e)));
                    showRetry(this::downloadAudio);
                });
            }
        });
    }

    /** 组装落盘后要写入的标签：set 补专辑名 / 曲序，单曲补风格与年份。 */
    private static AudioTagger.Tags tagsFor(String title, String artist,
                                            SoundCloudApiClient.Track track,
                                            SoundCloudSetClient.Set set, int trackNo) {
        AudioTagger.Tags tags = new AudioTagger.Tags();
        tags.title = title;
        tags.artist = artist;
        if (set != null) {
            tags.album = set.title;
            tags.albumArtist = set.artist;
            tags.coverUrl = set.artworkUrl;
            tags.trackNo = trackNo;
        } else if (track != null) {
            tags.genre = track.genre;
            tags.year = yearOf(track.releaseDate);
            tags.coverUrl = track.coverUrl;
        }
        return tags;
    }

    private static String yearOf(String date) {
        return date != null && date.length() >= 4 && Character.isDigit(date.charAt(0))
                ? date.substring(0, 4) : "";
    }

    /**
     * 下载文件名用的标题。
     *
     * <p>元数据没解析成功时（常见于没先点「解析」、或解析被风控拦下），
     * 过去统一落到 {@code soundcloud-audio}，多个文件会互相覆盖也看不出是哪首；
     * 这里退到链接末尾的 slug（{@code …/purple-of-midnight} → {@code purple of midnight}）。
     */
    private static String titleOf(String title, String url) {
        if (!TextUtils.isEmpty(title)) {
            return title.trim();
        }
        String path = url == null ? "" : url.trim();
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String slug = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            slug = java.net.URLDecoder.decode(slug, "UTF-8");
        } catch (Exception ignored) {
            // 非法转义时按原文使用
        }
        slug = slug.replace('-', ' ').replace('_', ' ').trim();
        return slug.isEmpty() ? "soundcloud-audio" : slug;
    }

    /** 取宿主 Activity：过 Cloudflare 与兜底询问都需要界面上下文 */
    @Nullable
    private Activity activity() {
        Context context = getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.CHINA, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String messageOf(Exception e) {
        String message = e.getMessage();
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void toast(String text) {
        Feedback.show(this, text);
    }
}
