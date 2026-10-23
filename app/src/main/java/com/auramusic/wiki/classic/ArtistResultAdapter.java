package com.auramusic.wiki.classic;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 艺人搜索结果适配器。
 *
 * <p>上传页的一级实时搜索与「添加艺人」面板共用同一套渲染逻辑；
 * 头像统一走 {@link CoverLoader}（复用其内存 / 磁盘缓存与域名白名单），
 * 避免每次搜索结果都重新下载。
 */
final class ArtistResultAdapter extends RecyclerView.Adapter<ArtistResultAdapter.Holder> {

    interface OnPick {
        void onPick(AlbumDraft.Artist artist);
    }

    private final List<AlbumDraft.Artist> items = new ArrayList<>();
    private final OnPick pick;
    /** 非空时在列表顶部额外插入一行「直接添加「关键词」」。 */
    private String customKeyword = "";

    ArtistResultAdapter(OnPick pick) {
        this.pick = pick;
    }

    void submit(List<AlbumDraft.Artist> data) {
        submit(data, null);
    }

    /**
     * 用搜索结果刷新列表；当 {@code keyword} 非空时，会在顶部保留一行
     * 「直接添加『关键词』」，与原 APK / 网页版一致。
     */
    void submit(List<AlbumDraft.Artist> data, String keyword) {
        customKeyword = keyword == null ? "" : keyword.trim();
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    /**
     * 无匹配结果时改为渲染「直接添加」行：点击即以该关键词新建一位曲库外的艺人
     * （artistId 为空，与原 APK / 网页版一致）。
     */
    void submitCustom(String keyword) {
        submit(null, keyword);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_artist_result, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (!customKeyword.isEmpty() && position == 0) {
            holder.bindCustom(customKeyword, pick);
            return;
        }
        holder.bind(items.get(position - (!customKeyword.isEmpty() ? 1 : 0)), pick);
    }

    @Override
    public int getItemCount() {
        return items.size() + (customKeyword.isEmpty() ? 0 : 1);
    }

    static final class Holder extends RecyclerView.ViewHolder {

        private final ImageView avatar;
        private final TextView name;
        private final TextView id;

        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.iv_artist_avatar);
            name = itemView.findViewById(R.id.tv_artist_name);
            id = itemView.findViewById(R.id.tv_artist_id);
        }

        void bind(final AlbumDraft.Artist artist, final OnPick pick) {
            if (artist == null) {
                return;
            }
            name.setText(TextUtils.isEmpty(artist.name) ? "未知艺人" : artist.name);
            id.setText("ID " + artist.id);

            avatar.setImageResource(R.drawable.ic_account_muted);
            avatar.setTag(null);
            String avatarUrl = artist.avatar;
            // 网易云 CDN 同时支持 https；Android 9+ 默认禁止明文流量，这里统一升级
            if (avatarUrl != null && avatarUrl.startsWith("http://")) {
                avatarUrl = "https://" + avatarUrl.substring("http://".length());
            }
            if (!TextUtils.isEmpty(avatarUrl) && CoverLoader.isAllowed(avatarUrl)) {
                int size = Math.round(36 * itemView.getResources()
                        .getDisplayMetrics().density);
                CoverLoader.get().load(avatar, avatarUrl, size);
            }

            itemView.setOnClickListener(v -> {
                HapticsManager.tap(v);
                if (pick != null) {
                    pick.onPick(artist);
                }
            });
        }

        /** 「无匹配结果直接添加」行：以关键词新建一位曲库外的艺人。 */
        void bindCustom(final String keyword, final OnPick pick) {
            name.setText(itemView.getContext().getString(R.string.au_artist_add_custom, keyword));
            id.setText(R.string.au_artist_add_custom_hint);
            avatar.setTag(null);
            avatar.setImageResource(R.drawable.ic_add_muted);
            itemView.setOnClickListener(v -> {
                HapticsManager.tap(v);
                if (pick != null) {
                    pick.onPick(new AlbumDraft.Artist(0, keyword, ""));
                }
            });
        }
    }
}
