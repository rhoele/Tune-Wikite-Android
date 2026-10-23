package com.auramusic.wiki.classic;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 下载文件仓库：封面 / 音频的保存目录、占用统计、清理、迁移与自动清理。
 *
 * <p>下载本体保存在应用专用外部目录（卸载即清理，无需存储权限）；设置页可以选择一个
 * SAF 目录，下载完成后自动导出一份到该目录，便于用户在其他应用中直接取用。
 */
final class DownloadStore {

    private static final String TAG = "DownloadStore";

    static final int KIND_COVER = 0;
    static final int KIND_AUDIO = 1;

    private static final String KEY_COVER_TREE = "download_cover_tree_uri";
    private static final String KEY_AUDIO_TREE = "download_audio_tree_uri";
    private static final String KEY_AUTO_MB = "download_auto_clean_mb";
    private static final String KEY_AUTO_DAYS = "download_auto_clean_days";
    /** 关闭开关前的有效值，重新开启时恢复 */
    private static final String KEY_AUTO_MB_LAST = "download_auto_clean_mb_last";
    private static final String KEY_AUTO_DAYS_LAST = "download_auto_clean_days_last";
    private static final String KEY_LAST_CLEAN = "download_last_clean_at";

    /** 自动清理的串行锁：多个入口同时触发时只允许一轮删除，避免互相覆盖时间戳。 */
    private static final Object CLEAN_LOCK = new Object();
    /** 自动清理只删「一段时间没动过」的文件：刚下载 / 正在上传的文件可能仍被其它页面引用。 */
    private static final long AUTO_CLEAN_MIN_AGE_MS = 10L * 60L * 1000L;

    private DownloadStore() {
    }

    // ------------------------------------------------------------------
    // 目录与统计
    // ------------------------------------------------------------------

    static File dir(Context context, int kind) {
        String name = kind == KIND_COVER ? "sc-cover" : "sc-audio";
        File base = context.getExternalFilesDir(null);
        return new File(base == null ? context.getFilesDir() : base, name);
    }

    /** 统计目录占用（递归文件大小）；目录不存在返回 0。 */
    static long sizeOf(File dir) {
        return sizeOf(dir, 0);
    }

    private static long sizeOf(File dir, int depth) {
        if (dir == null || depth > 8 || !dir.exists()) {
            return 0L;
        }
        if (dir.isFile()) {
            return dir.length();
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            total += sizeOf(child, depth + 1);
        }
        return total;
    }

    static long sizeOf(Context context, int kind) {
        return sizeOf(dir(context, kind));
    }

    static long totalSize(Context context) {
        return sizeOf(context, KIND_COVER) + sizeOf(context, KIND_AUDIO);
    }

    static int countFiles(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                count += countFiles(child);
            } else {
                count++;
            }
        }
        return count;
    }

    /** 未设置自定义目录时展示应用目录；设置了则展示 SAF 目录名。 */
    static String displayDir(Context context, int kind) {
        String tree = customTree(context, kind);
        if (TextUtils.isEmpty(tree)) {
            return dir(context, kind).getAbsolutePath();
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(tree));
            return id == null ? tree : id.replace("primary:", "内部存储/");
        } catch (Exception e) {
            return tree;
        }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024d;
        if (kb < 1024d) {
            return String.format(Locale.getDefault(), "%.0f KB", kb);
        }
        double mb = kb / 1024d;
        if (mb < 1024d) {
            return String.format(Locale.getDefault(), "%.1f MB", mb);
        }
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024d);
    }

    // ------------------------------------------------------------------
    // 自定义导出目录（SAF）
    // ------------------------------------------------------------------

    @Nullable
    static String customTree(Context context, int kind) {
        return WikiPrefs.get(context).getString(
                kind == KIND_COVER ? KEY_COVER_TREE : KEY_AUDIO_TREE, "");
    }

    static void setCustomTree(Context context, int kind, @Nullable Uri tree) {
        SharedPreferences.Editor editor = WikiPrefs.get(context).edit();
        if (kind == KIND_COVER) {
            editor.putString(KEY_COVER_TREE, tree == null ? "" : tree.toString());
        } else {
            editor.putString(KEY_AUDIO_TREE, tree == null ? "" : tree.toString());
        }
        editor.apply();
        if (tree != null) {
            try {
                context.getContentResolver().takePersistableUriPermission(tree,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(TAG, "无法持久化目录权限：" + e.getMessage());
            }
        }
    }

    /** 下载完成后把文件导出一份到自定义目录（未设置则跳过）。 */
    static void exportAsync(Context context, int kind, @Nullable File file) {
        if (context == null || file == null || !file.exists()) {
            return;
        }
        final String tree = customTree(context, kind);
        if (TextUtils.isEmpty(tree)) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Uri uri = Uri.parse(tree);
        new Thread(() -> {
            try {
                copyToTree(app, uri, file, mimeOf(file));
            } catch (Exception e) {
                Log.w(TAG, "导出到自定义目录失败：" + e.getMessage());
            }
        }, "download-export").start();
    }

    /** 把选中的文件复制到目标目录，然后删除应用目录内的原文件。 */
    static String moveToTree(Context context, List<File> files, Uri tree) {
        if (files == null || files.isEmpty() || tree == null) {
            return "";
        }
        int ok = 0;
        int failed = 0;
        for (File file : files) {
            try {
                copyToTree(context, tree, file, mimeOf(file));
                if (!file.delete()) {
                    failed++;
                } else {
                    ok++;
                }
            } catch (Exception e) {
                failed++;
                Log.w(TAG, "迁移失败 " + file.getName() + "：" + e.getMessage());
            }
        }
        return ok + "/" + (ok + failed);
    }

    private static Uri copyToTree(Context context, Uri tree, File file, String mime)
            throws IOException {
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
        Uri created = DocumentsContract.createDocument(
                context.getContentResolver(), parent, mime, file.getName());
        if (created == null) {
            throw new IOException("无法在目标目录创建文件");
        }
        try (InputStream in = new FileInputStream(file);
             OutputStream out = context.getContentResolver().openOutputStream(created)) {
            if (out == null) {
                throw new IOException("无法写入目标文件");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return created;
    }

    private static String mimeOf(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".wav")) {
            return "audio/wav";
        }
        if (name.endsWith(".flac")) {
            return "audio/flac";
        }
        if (name.endsWith(".m4a")) {
            return "audio/mp4";
        }
        return "audio/mpeg";
    }

    // ------------------------------------------------------------------
    // 删除与列表
    // ------------------------------------------------------------------

    /** 删除某一类的全部文件；返回是否全部删除成功。 */
    static boolean deleteAll(Context context, int kind) {
        return deleteRecursive(dir(context, kind));
    }

    private static boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            boolean ok = true;
            if (children != null) {
                for (File child : children) {
                    ok &= deleteRecursive(child);
                }
            }
            return ok;
        }
        return file.delete();
    }

    /** 音频文件列表（按修改时间倒序）。 */
    static List<File> audioFiles(Context context) {
        List<File> out = new ArrayList<>();
        File[] files = dir(context, KIND_AUDIO).listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && isAudio(file.getName())) {
                    out.add(file);
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private static boolean isAudio(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav")
                || lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".ogg");
    }

    // ------------------------------------------------------------------
    // 自动清理
    // ------------------------------------------------------------------

    static int autoCleanThresholdMb(Context context) {
        return WikiPrefs.get(context).getInt(KEY_AUTO_MB, 0);
    }

    static void setAutoCleanThresholdMb(Context context, int mb) {
        SharedPreferences prefs = WikiPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_AUTO_MB, Math.max(0, mb));
        if (mb > 0) {
            // 记住最近一次的有效上限，关闭后再开启可恢复
            editor.putInt(KEY_AUTO_MB_LAST, mb);
        }
        editor.apply();
    }

    /** 开关「存储用量阈值」：关闭时保留上次的上限，重新开启即恢复。 */
    static void setAutoCleanThresholdEnabled(Context context, boolean enabled) {
        if (enabled) {
            int last = WikiPrefs.get(context).getInt(KEY_AUTO_MB_LAST, 1024);
            setAutoCleanThresholdMb(context, last > 0 ? last : 1024);
        } else {
            setAutoCleanThresholdMb(context, 0);
        }
    }

    static int autoCleanDays(Context context) {
        return WikiPrefs.get(context).getInt(KEY_AUTO_DAYS, 0);
    }

    static void setAutoCleanDays(Context context, int days) {
        SharedPreferences prefs = WikiPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit().putInt(KEY_AUTO_DAYS, Math.max(0, days));
        if (days > 0) {
            // 周期从「这次设置」开始计算，避免刚设置就判定为到期
            editor.putInt(KEY_AUTO_DAYS_LAST, days);
            editor.putLong(KEY_LAST_CLEAN, System.currentTimeMillis());
        }
        editor.apply();
    }

    /** 开关「定时清理」：关闭时保留上次的周期，重新开启即恢复。 */
    static void setAutoCleanDaysEnabled(Context context, boolean enabled) {
        if (enabled) {
            int last = WikiPrefs.get(context).getInt(KEY_AUTO_DAYS_LAST, 30);
            setAutoCleanDays(context, last > 0 ? last : 30);
        } else {
            setAutoCleanDays(context, 0);
        }
    }

    /**
     * 达到用量阈值或到达清理周期时删除全部下载文件。
     *
     * <p>全部在后台线程完成：统计目录大小可能涉及上百个文件，不能卡住界面。
     */
    static void maybeAutoClean(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = WikiPrefs.get(app);
        final int mb = prefs.getInt(KEY_AUTO_MB, 0);
        final int days = prefs.getInt(KEY_AUTO_DAYS, 0);
        if (mb <= 0 && days <= 0) {
            return;
        }
        new Thread(() -> {
            synchronized (CLEAN_LOCK) {
                boolean need = false;
                if (mb > 0 && totalSize(app) >= mb * 1024L * 1024L) {
                    need = true;
                }
                if (!need && days > 0) {
                    long last = prefs.getLong(KEY_LAST_CLEAN, 0L);
                    if (last <= 0L) {
                        // 首次启用周期清理：以当前时间为起点，避免刚设置完就清空已有下载
                        prefs.edit().putLong(KEY_LAST_CLEAN, System.currentTimeMillis()).apply();
                        return;
                    }
                    need = System.currentTimeMillis() - last >= days * 24L * 3600L * 1000L;
                }
                if (!need) {
                    return;
                }
                // 最近 10 分钟内写入的文件可能正在下载 / 已被交接给上传页，先跳过
                long cutoff = System.currentTimeMillis() - AUTO_CLEAN_MIN_AGE_MS;
                boolean ok = deleteStale(dir(app, KIND_COVER), cutoff);
                ok &= deleteStale(dir(app, KIND_AUDIO), cutoff);
                if (ok) {
                    prefs.edit().putLong(KEY_LAST_CLEAN, System.currentTimeMillis()).apply();
                } else {
                    // 删除失败不推进时间戳，下个周期会再次尝试，避免「假成功」长期掩盖问题
                    Log.w(TAG, "自动清理未完全成功，下次周期重试");
                }
                Log.i(TAG, "自动清理完成：阈值=" + mb + "MB 周期=" + days + "天");
            }
        }, "download-auto-clean").start();
    }

    /** 递归删除早于 cutoff 的文件；返回是否全部成功（太新的文件视为跳过，不算失败）。 */
    private static boolean deleteStale(File file, long cutoff) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            boolean ok = true;
            if (children != null) {
                for (File child : children) {
                    ok &= deleteStale(child, cutoff);
                }
            }
            return ok;
        }
        if (file.lastModified() > cutoff) {
            return true;
        }
        return file.delete();
    }
}
