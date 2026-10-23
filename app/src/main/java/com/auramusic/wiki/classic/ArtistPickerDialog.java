package com.auramusic.wiki.classic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 艺人选择面板。
 *
 * <p>空关键词时优先展示 {@code suggestions}（即用户刚移除 / 上轮提交的艺人），
 * 使「移除后作为首选项可被再次填回去」；输入关键词后改为服务端搜索。
 * 列表渲染与上传页的一级实时搜索共用 {@link ArtistResultAdapter}，头像走同一套缓存。
 *
 * <p>Stage 16：由 {@code AlertDialog} 改为 M3 {@link BottomSheetDialog} ——
 * 搜索框 + 结果列表属于「选择型」场景，底部抽屉能保留更多列表空间，
 * 也符合 Expressive 的模态层级（对话框只留给确认 / 破坏性操作）。
 */
final class ArtistPickerDialog {

    interface OnPick {
        void onPick(AlbumDraft.Artist artist);
    }

    private ArtistPickerDialog() {
    }

    static void show(Context context, List<AlbumDraft.Artist> suggestions, OnPick onPick) {
        View root = LayoutInflater.from(context).inflate(R.layout.dialog_artist_picker, null);
        TextInputEditText search = root.findViewById(R.id.et_artist_search);
        RecyclerView list = root.findViewById(R.id.rv_artist_results);
        TextView empty = root.findViewById(R.id.tv_artist_empty);
        TextView suggestTitle = root.findViewById(R.id.tv_artist_suggest);
        LinearProgressIndicator progress = root.findViewById(R.id.pb_artist_search);

        final BottomSheetDialog[] holder = new BottomSheetDialog[1];
        ArtistResultAdapter adapter = new ArtistResultAdapter(artist -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            onPick.onPick(artist);
        });
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        adapter.submit(suggestions);
        suggestTitle.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(root);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        holder[0] = dialog;
        dialog.show();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String keyword = s == null ? "" : s.toString().trim();
                if (keyword.isEmpty()) {
                    suggestTitle.setVisibility(suggestions.isEmpty() ? View.GONE : View.VISIBLE);
                    empty.setVisibility(View.GONE);
                    adapter.submit(suggestions);
                    return;
                }
                suggestTitle.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                executor.execute(() -> {
                    try {
                        List<AlbumDraft.Artist> results = NetEaseWikiClient.searchArtists(
                                context.getApplicationContext(), keyword, null);
                        main.post(() -> {
                            progress.setVisibility(View.GONE);
                            empty.setVisibility(View.GONE);
                            // 与原 APK / 网页版一致：只要用户有输入，就始终保留
                            // 「直接添加『关键词』」入口，搜索结果显示在其下方。
                            adapter.submit(results, keyword);
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            progress.setVisibility(View.GONE);
                            empty.setVisibility(View.VISIBLE);
                            // 异常文案可能是 null，兜底给一句可读提示
                            Feedback.show(root, TextUtils.isEmpty(e.getMessage())
                                    ? context.getString(R.string.au_artist_search_failed)
                                    : e.getMessage());
                        });
                    }
                });
            }
        });

        dialog.setOnDismissListener(d -> executor.shutdownNow());
    }
}
