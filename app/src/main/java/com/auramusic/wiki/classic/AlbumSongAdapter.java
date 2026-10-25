package com.auramusic.wiki.classic;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

/** 专辑歌曲列表适配器：展示碟序 / 曲序 / 上传进度，并支持从左侧手柄发起拖拽。 */
final class AlbumSongAdapter extends RecyclerView.Adapter<AlbumSongAdapter.Holder> {

    interface Listener {
        void onSongClick(AlbumDraft.Song song);

        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    private List<AlbumDraft.Song> songs = new ArrayList<>();
    private Listener listener;

    /** 仅刷新「碟序 / 曲序」等元信息，避免拖拽时整行重建导致的闪烁。 */
    private static final Object PAYLOAD_META = new Object();
    /** 刷新一行里可能变化的内容（标题 / 元信息 / 进度 / 状态），但不重建整行。 */
    private static final Object PAYLOAD_CONTENT = new Object();

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 直接持有页面维护的歌曲列表（不拷贝）。
     *
     * <p>拖拽排序、插入、删除都以同一份列表为准，避免「适配器顺序已变、页面顺序未变」
     * 导致曲序重排与提交数据错位。
     */
    void attach(List<AlbumDraft.Song> data) {
        songs = data == null ? new ArrayList<>() : data;
        notifyDataSetChanged();
    }

    AlbumDraft.Song get(int position) {
        return songs.get(position);
    }

    void move(int from, int to) {
        AlbumDraft.Song song = songs.remove(from);
        songs.add(to, song);
        notifyItemMoved(from, to);
    }

    void refreshSong(AlbumDraft.Song song) {
        int index = songs.indexOf(song);
        if (index >= 0) {
            notifyItemChanged(index, PAYLOAD_CONTENT);
        }
    }

    /** 曲序重排后只更新元信息，不重建标题 / 进度等视图。 */
    void notifyTrackNumbersChanged() {
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_META);
    }

    /**
     * 拖拽换位结束后整行重绑一次。
     *
     * <p>RecyclerView 对 {@code notifyItemMoved} 不做重绑，换位后若某行在拖拽期间被
     * 回收 / 复用，上传进度与完成对勾可能停留在旧内容上；这里显式刷新，保证每行显示的
     * 曲序、进度、对勾都与当前歌曲一致。
     */
    void refreshAfterDrag() {
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_CONTENT);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_album_song, parent, false);
        return new Holder(view);
    }

    // 拖动把手在 ACTION_DOWN 就要开始拖拽，不能等 ACTION_UP 的标准点击；
    // 已在 ACTION_UP 主动调用 performClick() 满足无障碍要求，故这里抑制该规则。
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        // 拖拽中的行可能带着放大 / 阴影被回收复用，绑定新内容前先复位，
        // 保证拖拽结束后所有行的外观一致
        holder.itemView.animate().cancel();
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.itemView.setTranslationZ(0f);

        AlbumDraft.Song song = songs.get(position);
        holder.name.setText(song.name);
        bindMeta(holder, song);
        holder.progress.setVisibility(song.uploading ? View.VISIBLE : View.GONE);
        holder.progress.setProgressCompat(song.uploadPercent, true);
        holder.state.setVisibility(song.uploaded() ? View.VISIBLE : View.GONE);

        holder.body.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSongClick(song);
            }
        });
        holder.drag.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && listener != null) {
                listener.onStartDrag(holder);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                // 无障碍：拖动把手也要产生一次标准点击事件，TalkBack 才能识别该控件
                v.performClick();
            }
            return false;
        });
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_META)) {
            bindMeta(holder, songs.get(position));
            return;
        }
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_CONTENT)) {
            AlbumDraft.Song song = songs.get(position);
            // 标题必须一起刷新：编辑弹窗改完名称后只刷进度的话，列表会一直显示旧名称
            holder.name.setText(song.name);
            bindMeta(holder, song);
            holder.progress.setVisibility(song.uploading ? View.VISIBLE : View.GONE);
            holder.progress.setProgressCompat(song.uploadPercent, true);
            holder.state.setVisibility(song.uploaded() ? View.VISIBLE : View.GONE);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    private void bindMeta(Holder holder, AlbumDraft.Song song) {
        StringBuilder meta = new StringBuilder();
        meta.append(holder.itemView.getContext().getString(R.string.au_song_disc_track,
                Math.max(1, song.disc), Math.max(1, song.track)));
        if (!song.artists.isEmpty()) {
            meta.append(" · ");
            for (int i = 0; i < song.artists.size(); i++) {
                if (i > 0) {
                    meta.append(", ");
                }
                meta.append(song.artists.get(i).name);
            }
        }
        if (song.uploading) {
            meta.append(" · ").append(holder.itemView.getContext()
                    .getString(R.string.au_song_uploading, song.uploadPercent));
        } else if (song.uploadError != null && !song.uploadError.isEmpty()) {
            meta.append(" · ").append(song.uploadError);
        }
        holder.meta.setText(meta);
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView drag;
        final View body;
        final TextView name;
        final TextView meta;
        final LinearProgressIndicator progress;
        final ImageView state;

        Holder(@NonNull View itemView) {
            super(itemView);
            drag = itemView.findViewById(R.id.iv_song_drag);
            body = itemView.findViewById(R.id.song_body);
            name = itemView.findViewById(R.id.tv_song_name);
            meta = itemView.findViewById(R.id.tv_song_meta);
            progress = itemView.findViewById(R.id.pb_song);
            state = itemView.findViewById(R.id.iv_song_state);
        }
    }
}
