package com.auramusic.wiki.classic;

import android.app.Activity;
import android.text.TextUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 声云音源的统一取流链路（三条线，按优先级回落）。
 *
 * <ol>
 *   <li><b>lucida API</b>：恢复旧链路的全部格式选项，普通 HTTP 直连；</li>
 *   <li><b>lucida + 浏览器会话</b>：被 Cloudflare 拦下时，用 WebView 过验证拿 cf_clearance，
 *       再用同一 UA / Cookie 重试（可拿到 m4a-aac-320 等服务端转码产物）；</li>
 *   <li><b>声云直连</b>：前两条都失效时询问用户，同意后走 SoundCloud 官方 api-v2
 *       （只有公开音源，通常 96–160 kbps）。</li>
 * </ol>
 *
 * <p>上传者开放下载的「原文件」不在应用内获取：SoundCloud 的
 * {@code /tracks/{id}/download} 已要求网页登录且普通登录令牌仍返回 403，
 * 需要用户在浏览器里手动下载。
 *
 * <p>每次落盘后统一走 {@link AudioTagger} 补齐标题 / 艺人 / 专辑 / 封面等元数据。
 */
final class SoundCloudDownloader {

    interface Progress {
        void onStatus(String text);

        void onPercent(int percent);
    }

    private enum Choice { DIRECT, RETRY, STOP }

    /** 用户对「是否允许直连兜底」的选择：null 未询问 / TRUE 允许 / FALSE 停止 */
    private static volatile Boolean allowDirect;

    private SoundCloudDownloader() {
    }

    /** 每批下载开始时重新询问，避免一次拒绝影响后续所有任务 */
    static void resetPrompt() {
        allowDirect = null;
    }

    /**
     * 按优先级取一首曲目的音频文件，并在落盘后写入元数据。
     *
     * @param lucidaFormat lucida 的 downscale 取值（下拉框原值）
     * @param tags         为空表示不写标签
     * @param activity     用于过 Cloudflare 与询问兜底；为空时跳过需要界面的步骤
     */
    static File download(Activity activity, String pageUrl, String lucidaFormat, File dir,
                         String title, AudioTagger.Tags tags, Progress progress) throws IOException {
        int retried = 0;
        while (true) {
            IOException last;
            // 一 / 二：lucida API。持有 cf_clearance 时第一步就能成功，
            // 只有真的被 Cloudflare 拦下（403/503）才需要开浏览器会话。
            try {
                return lucida(activity, pageUrl, lucidaFormat, dir, title, tags, progress);
            } catch (IOException e) {
                last = e;
            }
            // 三：询问用户「确定直连 / 重试 lucida / 停止」
            Choice choice;
            if (Boolean.TRUE.equals(allowDirect)) {
                choice = Choice.DIRECT;
            } else if (Boolean.FALSE.equals(allowDirect)) {
                choice = Choice.STOP;
            } else {
                choice = ask(activity, last);
                if (choice == Choice.DIRECT) {
                    allowDirect = Boolean.TRUE;
                } else if (choice == Choice.STOP) {
                    allowDirect = Boolean.FALSE;
                }
            }
            if (choice == Choice.DIRECT) {
                return finish(SoundCloudApiClient.download(pageUrl, mapFormat(lucidaFormat), dir,
                        title, new SoundCloudApiClient.Progress() {
                            @Override
                            public void onStatus(String text) {
                                status(activity, progress, text);
                            }

                            @Override
                            public void onPercent(int percent) {
                                percent(progress, percent);
                            }
                        }), tags);
            }
            if (choice == Choice.STOP) {
                throw new IOException(activity == null ? "已停止下载"
                        : activity.getString(R.string.sc_fallback_stopped));
            }
            // 重试：强制丢弃旧会话，下一轮重新过验证；最多两轮，避免无限弹窗
            LucidaClient.setSession("", "");
            if (++retried > 2) {
                throw last;
            }
        }
    }

    /**
     * lucida 通道：先按当前会话直连，被 Cloudflare 拦下时过一次浏览器验证再试。
     *
     * <p>不做「挑战过就永远走浏览器」的短路：持有 {@code cf_clearance} 时普通请求本身就能过，
     * 短路反而会让后续下载全部绕到验证链路。
     */
    private static File lucida(Activity activity, String pageUrl, String format, File dir,
                               String title, AudioTagger.Tags tags, Progress progress)
            throws IOException {
        try {
            return finish(LucidaClient.download(pageUrl, format, "", title, dir,
                    lucidaProgress(progress)), tags);
        } catch (IOException e) {
            if (!LucidaClient.isChallenge(e) || activity == null) {
                throw e;
            }
            // 通行证有寿命：先丢掉旧会话，再让 WebView 重新过验证
            LucidaClient.setSession("", "");
            status(activity, progress, R.string.sc_lucida_verifying);
            if (!LucidaSession.ensure(activity, 25_000L)) {
                throw e;
            }
            return finish(LucidaClient.download(pageUrl, format, "", title, dir,
                    lucidaProgress(progress)), tags);
        }
    }

    private static File finish(File file, AudioTagger.Tags tags) {
        AudioTagger.write(file, tags);
        return file;
    }

    /** lucida 格式 → 直连只能表达的容器偏好 */
    private static String mapFormat(String lucidaFormat) {
        if (lucidaFormat == null) {
            return "auto";
        }
        if (lucidaFormat.startsWith("mp3")) {
            return "mp3";
        }
        if (lucidaFormat.startsWith("opus")) {
            return "opus";
        }
        if (lucidaFormat.startsWith("m4a") || "original".equals(lucidaFormat)) {
            return "aac";
        }
        return "auto";
    }

    private static LucidaClient.Progress lucidaProgress(final Progress progress) {
        return new LucidaClient.Progress() {
            @Override
            public void onStatus(String text) {
                if (progress != null) {
                    progress.onStatus(text);
                }
            }

            @Override
            public void onPercent(int percent) {
                if (progress != null) {
                    progress.onPercent(percent);
                }
            }
        };
    }

    /** 主线程弹窗询问；调用线程阻塞等待用户选择 */
    private static Choice ask(Activity activity, IOException reason) {
        if (activity == null || activity.isFinishing()) {
            return Choice.STOP;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Choice> result = new AtomicReference<>(Choice.STOP);
        final String detail = reason == null || TextUtils.isEmpty(reason.getMessage())
                ? "" : reason.getMessage();
        activity.runOnUiThread(() -> {
            try {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.sc_fallback_title)
                        .setMessage(activity.getString(R.string.sc_fallback_message, detail))
                        .setPositiveButton(R.string.sc_fallback_use_direct, (dialog, which) -> {
                            result.set(Choice.DIRECT);
                            latch.countDown();
                        })
                        .setNeutralButton(R.string.sc_fallback_retry, (dialog, which) -> {
                            result.set(Choice.RETRY);
                            latch.countDown();
                        })
                        .setNegativeButton(R.string.sc_fallback_stop, (dialog, which) -> {
                            result.set(Choice.STOP);
                            latch.countDown();
                        })
                        .setCancelable(false)
                        .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static void status(Activity activity, Progress progress, int stringRes) {
        if (activity != null) {
            status(activity, progress, activity.getString(stringRes));
        }
    }

    private static void status(Activity activity, Progress progress, String text) {
        if (progress != null) {
            progress.onStatus(text);
        }
    }

    private static void percent(Progress progress, int percent) {
        if (progress != null) {
            progress.onPercent(percent);
        }
    }
}
