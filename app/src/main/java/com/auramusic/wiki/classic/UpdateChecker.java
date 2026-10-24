package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：查询 GitHub Releases 的最新正式版、下载 APK 并交给系统安装。
 *
 * <p>本应用通过自建仓库分发，没有商店的更新通道，因此以 release tag 的版本号作为唯一版本源。
 * 只做「手动检查」，不后台轮询、不静默下载；APK 的完整性由系统安装器按签名校验兜底。
 */
final class UpdateChecker {

    /** 发布仓库的最新正式版（GitHub 会跳过 draft 与 prerelease）。 */
    static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/rhoele/Tune-Wikite-Android/releases/latest";

    private static final String USER_AGENT = "TuneWikite-Android";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 20000;
    /** GitHub 下载会跳转到 objects.githubusercontent.com，留够跳数 */
    private static final int MAX_REDIRECT_HOPS = 5;
    private static final int BUFFER_SIZE = 64 * 1024;

    private UpdateChecker() {
    }

    /** 一个可安装的正式版。 */
    static final class Release {
        final String tag;
        /** 去掉前缀 v 的版本号，例如 1.1.0 */
        final String version;
        final String notes;
        /** 没有 APK 附件时为 null */
        final String apkUrl;
        /** 服务端声明的字节数，未知时为 0 */
        final long apkSize;

        Release(String tag, String version, String notes, String apkUrl, long apkSize) {
            this.tag = tag;
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    interface Progress {
        void onPercent(int percent);
    }

    /** 查询最新正式版；网络与解析异常统一抛 {@link WikiException}。 */
    static Release latest(Http.CancelToken token) throws WikiException {
        String raw = Http.get(LATEST_RELEASE_API, USER_AGENT, null, null, token,
                CONNECT_TIMEOUT, READ_TIMEOUT, 512 * 1024);
        try {
            return parse(raw);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.BAD_JSON, "版本信息解析失败", e);
        }
    }

    /** 解析 releases/latest 的响应体。抽出来便于单测，不依赖网络。 */
    static Release parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        String tag = root.optString("tag_name", "").trim();
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("缺少 tag_name");
        }
        String version = stripPrefix(tag);
        String notes = root.optString("body", "").trim();

        String apkUrl = null;
        long apkSize = 0;
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", null);
                    apkSize = asset.optLong("size", 0);
                    break;
                }
            }
        }
        return new Release(tag, version, notes, apkUrl, apkSize);
    }

    /** 把 "v1.1.0" / "1.1.0" 统一成 "1.1.0"。 */
    static String stripPrefix(String tag) {
        if (tag.isEmpty()) {
            return tag;
        }
        char first = tag.charAt(0);
        return first == 'v' || first == 'V' ? tag.substring(1).trim() : tag;
    }

    /**
     * 比较两个版本号：{@code a} 比 {@code b} 新返回正数，相同返回 0，旧返回负数。
     * 只按点分段做数值比较，遇到非数字段（如 beta）按其数字前缀处理，避免异常输入导致崩溃。
     */
    static int compareVersions(String a, String b) {
        String[] left = stripPrefix(safe(a)).split("\\.");
        String[] right = stripPrefix(safe(b)).split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int l = i < left.length ? leadingNumber(left[i]) : 0;
            int r = i < right.length ? leadingNumber(right[i]) : 0;
            if (l != r) {
                return l > r ? 1 : -1;
            }
        }
        return 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static int leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 当前安装包的版本名。 */
    static String currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return BuildConfig.VERSION_NAME;
        }
    }

    /**
     * 下载 APK 到应用缓存目录：先写 .part，长度校验通过后再改名，
     * 避免把半截文件交给安装器。
     */
    static File download(Context context, Release release, Progress progress,
                         Http.CancelToken token) throws WikiException {
        if (release.apkUrl == null || release.apkUrl.isEmpty()) {
            throw new WikiException(WikiException.Kind.EMPTY, "该版本没有可下载的安装包");
        }
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "无法创建更新目录");
        }
        File target = new File(dir, "TuneWikite-" + release.version + ".apk");
        File part = new File(dir, target.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "无法清理上次的临时文件");
        }

        HttpURLConnection conn = null;
        try {
            String url = release.apkUrl;
            for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
                conn = (HttpURLConnection) new URL(url).openConnection();
                if (token != null) {
                    token.attach(conn);
                }
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                // GitHub 的下载地址会 302 到对象存储，手工跟随，避免把 Cookie 带到别的域名
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/octet-stream");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("跳转地址缺失");
                    }
                    url = new URL(new URL(url), location).toString();
                    continue;
                }
                if (code != 200) {
                    throw new WikiException(WikiException.Kind.HTTP, "HTTP " + code, code, null);
                }
                String contentType = conn.getContentType();
                if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                    throw new IOException("下载地址返回了网页而不是安装包");
                }

                long total = conn.getContentLengthLong() > 0
                        ? conn.getContentLengthLong() : release.apkSize;
                long written = 0;
                InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                FileOutputStream fos = new FileOutputStream(part);
                BufferedOutputStream out = new BufferedOutputStream(fos, BUFFER_SIZE);
                try {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    int lastPercent = -1;
                    while ((read = in.read(buffer)) != -1) {
                        if (token != null && token.isCancelled()) {
                            throw new WikiException(WikiException.Kind.CANCELLED, "下载已取消");
                        }
                        out.write(buffer, 0, read);
                        written += read;
                        if (progress != null && total > 0) {
                            int percent = (int) Math.min(100, written * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                progress.onPercent(percent);
                            }
                        }
                    }
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                        // 关闭失败不影响后续校验
                    }
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // 同上
                    }
                }

                if (total > 0 && written != total) {
                    throw new IOException("下载不完整");
                }
                if (written == 0) {
                    throw new IOException("下载内容为空");
                }
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法覆盖旧安装包");
                }
                if (!part.renameTo(target)) {
                    throw new IOException("无法保存安装包");
                }
                return target;
            }
            throw new IOException("跳转次数过多");
        } catch (WikiException e) {
            throw e;
        } catch (IOException e) {
            if (token != null && token.isCancelled()) {
                throw new WikiException(WikiException.Kind.CANCELLED, "下载已取消", e);
            }
            throw new WikiException(WikiException.Kind.NO_NETWORK, "下载失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new WikiException(WikiException.Kind.UNKNOWN, "下载失败", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (token != null) {
                token.detach();
            }
            if (part.exists()) {
                // 失败路径统一清掉临时文件；成功时它已被改名，这里不会命中
                part.delete();
            }
        }
    }

    /** 系统是否允许本应用发起安装（Android 8+ 需要用户在系统设置里授权）。 */
    static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** 跳转到「安装未知应用」授权页。 */
    static void openInstallPermission(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** 用系统安装器打开已下载的 APK。 */
    static void install(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    /**
     * 把 Release 正文的 Markdown 压成纯文本：去掉 HTML 注释、标题井号、加粗标记与表格分隔行，
     * 否则页面里会直接出现 {@code <!-- -->} 与 {@code ##}。
     */
    static String plainNotes(String raw) {
        String text = raw.replaceAll("(?s)<!--.*?-->", "");
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("\\|?[\\s:|-]+\\|?")) {
                continue;
            }
            // 代码围栏整行丢弃，行内反引号与加粗标记去掉，正文里只留纯文字
            if (trimmed.startsWith("```")) {
                continue;
            }
            trimmed = trimmed.replaceAll("^#{1,6}\\s*", "");
            trimmed = trimmed.replace("**", "").replace("`", "");
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2) {
                trimmed = trimmed.substring(1, trimmed.length() - 1)
                        .replaceAll("\\s*\\|\\s*", " · ").trim();
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }
}
