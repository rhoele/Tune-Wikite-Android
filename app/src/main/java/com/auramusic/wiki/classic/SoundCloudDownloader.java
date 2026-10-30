package com.auramusic.wiki.classic;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;

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
 *   <li><b>lucida + 浏览器内通道</b>：被 Cloudflare 拦下时改用 WebView 页面内的 fetch 请求，
 *       挑战与业务请求出自同一个浏览器实例（可拿到 m4a-aac-320 等服务端转码产物）；</li>
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
        return download(activity, pageUrl, lucidaFormat, dir, title, tags, progress, null);
    }

    /**
     * 按优先级取一首曲目的音频文件，并在落盘后写入元数据。
     *
     * @param lucidaFormat lucida 的 downscale 取值（下拉框原值）
     * @param tags         为空表示不写标签
     * @param activity     用于过 Cloudflare 与询问兜底；为空时跳过需要界面的步骤
     * @param cancelled    取消信号（可为 null）：用户点「停止」后尽快结束当前通道
     */
    static File download(Activity activity, String pageUrl, String lucidaFormat, File dir,
                         String title, AudioTagger.Tags tags, Progress progress,
                         java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        CronetHttp.init(activity);
        int retried = 0;
        while (true) {
            if (cancelled != null && cancelled.get()) {
                throw new IOException("已取消下载");
            }
            IOException last;
            // 一 / 二：lucida API。直连就能成功时不会付出任何额外代价，
            // 只有真的被 Cloudflare 拦下（403/503）才开浏览器内通道。
            try {
                return lucida(activity, pageUrl, lucidaFormat, dir, title, tags, progress, cancelled);
            } catch (IOException e) {
                last = e;
            }
            if (cancelled != null && cancelled.get()) {
                throw new IOException("已取消下载");
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
            // 重试：下一轮会重新走浏览器通道；最多两轮，避免无限弹窗
            if (++retried > 2) {
                throw last;
            }
        }
    }

    /**
     * lucida 通道：先用原生栈直连，被 Cloudflare 拦下后切换到「浏览器内请求」。
     *
     * <p>不再走「先用 WebView 拿 cf_clearance、再交给原生栈」的旧路子：Cloudflare 把通行证
     * 与浏览器指纹（UA + TLS/HTTP2 + IP）绑在一起，换一个 HTTP 栈就会被判成新客户端继续 403。
     * 现在把后续所有请求（含音频文件）都放回同一个 WebView 页面里执行。
     */
    private static File lucida(Activity activity, String pageUrl, String format, File dir,
                               String title, AudioTagger.Tags tags, Progress progress,
                               java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws IOException {
        try {
            return finish(LucidaClient.download(pageUrl, format, "", title, dir,
                    lucidaProgress(progress), cancelled), tags);
        } catch (IOException e) {
            Log.w("LucidaFlow", "native failed: " + e.getMessage());
            if (!LucidaClient.isChallenge(e) || activity == null) {
                throw e;
            }
            status(activity, progress, R.string.sc_lucida_verifying);
            try (LucidaBrowser browser = LucidaBrowser.open(activity, 25_000L)) {
                LucidaClient.setTransport(browser);
                return finish(LucidaClient.download(pageUrl, format, "", title, dir,
                        lucidaProgress(progress), cancelled), tags);
            } catch (IOException browserError) {
                Log.w("LucidaFlow", "browser failed: " + browserError.getMessage());
                throw new IOException("lucida 浏览器通道失败：" + brief(browserError.getMessage()), e);
            } finally {
                LucidaClient.setTransport(null);
            }
        }
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String one = message.replace('\n', ' ').trim();
        return one.length() > 120 ? one.substring(0, 120) : one;
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
