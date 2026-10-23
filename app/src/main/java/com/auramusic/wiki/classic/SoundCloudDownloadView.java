package com.auramusic.wiki.classic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
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
    private LinearProgressIndicator progress;
    private Runnable retryAction;

    private LucidaClient.Track track;
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
                    // 明确的 /sets/ 链接解析失败时不要回退成单曲：
                    // lucida 只会报「Cannot stream this item type: album」，反而误导用户
                    if (url.toLowerCase(Locale.ROOT).contains("/sets/")) {
                        throw new Exception(getContext().getString(R.string.sc_set_parse_failed));
                    }
                }
                LucidaClient.Track resolved = LucidaClient.metadata(url);
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
        audioButton.setText(R.string.sc_action_download_audio);
        updateHandoff();
    }

    private void showTrack(LucidaClient.Track t) {
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
        final String downscale = convert ? "m4a-aac-320" : selectedFormat;
        final String account = track == null ? "" : track.account;
        final String title = track == null || TextUtils.isEmpty(track.title)
                ? "soundcloud-audio" : track.title;
        final String artist = track == null ? "" : track.artist;
        setBusy(true, getContext().getString(R.string.sc_progress_audio, 0));
        executor.execute(() -> {
            try {
                File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_AUDIO);
                File downloaded = LucidaClient.download(url, downscale, account, title, dir,
                        new LucidaClient.Progress() {
                            @Override
                            public void onStatus(String text) {
                                main.post(() -> statusView.setText(text));
                            }

                            @Override
                            public void onPercent(int percent) {
                                main.post(() -> {
                                    statusView.setText(getContext().getString(
                                            R.string.sc_progress_audio, percent));
                                });
                            }
                        });
                File result = downloaded;
                if (convert) {
                    main.post(() -> statusView.setText(R.string.sc_progress_convert));
                    File wav = new File(downloaded.getParentFile(),
                            stripExt(downloaded.getName()) + "-32bit.wav");
                    result = PcmWavConverter.toWav32(downloaded, wav,
                            text -> main.post(() -> statusView.setText(text)));
                }
                final File finalFile = result;
                DownloadStore.exportAsync(getContext(), DownloadStore.KIND_AUDIO, finalFile);
                main.post(() -> {
                    downloadedTracks.clear();
                    downloadedTracks.add(new DownloadedTrack(finalFile, title, artist));
                    hideRetry();
                    setBusy(false, getContext().getString(convert
                            ? R.string.sc_status_convert_ok : R.string.sc_status_audio_ok,
                            finalFile.getName()));
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

    /** 批量下载 set 里的全部曲目；单首失败不中断，最后汇报成功 / 失败数量。 */
    private void downloadSetAudio() {
        final SoundCloudSetClient.Set current = set;
        if (current == null || current.tracks.isEmpty()) {
            toast(getContext().getString(R.string.sc_set_empty));
            return;
        }
        final boolean convert = convertSwitch.isChecked();
        final String downscale = convert ? "m4a-aac-320" : selectedFormat;
        final int total = current.tracks.size();
        final int downloadable = Math.max(0, total - current.previewCount);
        if (downloadable == 0) {
            // 全部是 SNIP：lucida 对这类曲目只会抛 JS 异常，提前给出可读的原因
            toast(getContext().getString(R.string.sc_status_set_all_preview, current.previewCount));
            return;
        }
        if (current.unavailableCount > 0 || current.previewCount > 0) {
            toast(getContext().getString(R.string.sc_status_set_filtered,
                    total, current.unavailableCount, current.previewCount, downloadable));
        }
        setBusy(true, getContext().getString(R.string.sc_progress_set, 0, downloadable, 0));
        executor.execute(() -> {
            File dir = DownloadStore.dir(getContext(), DownloadStore.KIND_AUDIO);
            List<DownloadedTrack> out = new ArrayList<>();
            int failed = 0;
            int skippedPreview = 0;
            int processed = 0;
            for (int i = 0; i < total; i++) {
                final SoundCloudSetClient.Track item = current.tracks.get(i);
                if (item.previewOnly) {
                    // 仅试听曲目没有完整音源，直接跳过，避免把 JS 异常当成下载失败
                    skippedPreview++;
                    continue;
                }
                final int index = ++processed;
                try {
                    // 前缀序号既保证文件顺序，也避免同名曲目互相覆盖
                    String fileName = String.format(Locale.CHINA, "%02d - %s", index, item.title);
                    File downloaded = LucidaClient.download(item.url, downscale, "", fileName, dir,
                            new LucidaClient.Progress() {
                                @Override
                                public void onStatus(String text) {
                                    main.post(() -> statusView.setText(getContext().getString(
                                            R.string.sc_progress_set_title, index, downloadable, text)));
                                }

                                @Override
                                public void onPercent(int percent) {
                                    main.post(() -> statusView.setText(getContext().getString(
                                            R.string.sc_progress_set, index, downloadable, percent)));
                                }
                            });
                    File result = downloaded;
                    if (convert) {
                        File wav = new File(downloaded.getParentFile(),
                                stripExt(downloaded.getName()) + "-32bit.wav");
                        result = PcmWavConverter.toWav32(downloaded, wav, null);
                    }
                    out.add(new DownloadedTrack(result, item.title, item.artist));
                    DownloadStore.exportAsync(getContext(), DownloadStore.KIND_AUDIO, result);
                } catch (Exception e) {
                    failed++;
                }
            }
            final int failedCount = failed;
            final int previewSkipped = skippedPreview;
            main.post(() -> {
                downloadedTracks.clear();
                downloadedTracks.addAll(out);
                hideRetry();
                if (out.isEmpty()) {
                    if (previewSkipped > 0) {
                        setBusy(false, getContext().getString(
                                R.string.sc_status_set_all_preview, previewSkipped));
                    } else {
                        setBusy(false, getContext().getString(R.string.sc_audio_failed, ""));
                        showRetry(this::downloadAudio);
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
            hideRetry();
        }
        if (!TextUtils.isEmpty(status)) {
            statusView.setText(status);
        }
        updateHandoff();
    }

    private void showRetry(Runnable action) {
        retryAction = action;
        MotionManager.animateVisibility(getContext(), retryButton, true);
    }

    private void hideRetry() {
        retryAction = null;
        MotionManager.animateVisibility(getContext(), retryButton, false);
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
        if (message != null && message.contains("Cannot read properties of undefined")) {
            // lucida 对「只有试听片段」的 SoundCloud 曲目会直接抛 JS 异常，
            // 原文对用户毫无意义，这里统一换成可操作的原因说明
            return getContext().getString(R.string.sc_preview_only_error);
        }
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void toast(String text) {
        Feedback.show(this, text);
    }
}
