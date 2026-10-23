package com.auramusic.wiki.classic;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 音频文件列表：多选、批量迁移 / 删除。 */
final class AudioFileAdapter extends RecyclerView.Adapter<AudioFileAdapter.Holder> {

    static final class Item {
        final File file;
        final long size;
        final long modified;
        final String duration;

        Item(File file, long size, long modified, String duration) {
            this.file = file;
            this.size = size;
            this.modified = modified;
            this.duration = duration;
        }
    }

    interface Listener {
        void onToggle(Item item);
    }

    /** 排序维度 */
    enum Sort {
        TIME, NAME, SIZE
    }

    private final List<Item> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final Listener listener;
    private final java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);

    private Sort sort = Sort.TIME;
    /** true = 正序（时间早→晚 / A→Z / 小→大），false = 倒序 */
    private boolean ascending;

    AudioFileAdapter(Listener listener) {
        this.listener = listener;
        collator.setStrength(java.text.Collator.PRIMARY);
    }

    void submit(List<Item> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        Set<String> alive = new HashSet<>();
        for (Item item : items) {
            alive.add(item.file.getAbsolutePath());
        }
        selected.retainAll(alive);
        applyOrder();
    }

    /**
     * 切换排序：时间 / 名称 / 大小，并可正序 / 倒序。
     *
     * <p>名称排序按「首字母分组 + 组内自然顺序」进行，保证右侧字母索引与列表顺序一致。
     */
    void setSort(Sort mode, boolean ascending) {
        this.sort = mode == null ? Sort.TIME : mode;
        this.ascending = ascending;
        applyOrder();
    }

    Sort sortMode() {
        return sort;
    }

    boolean ascending() {
        return ascending;
    }

    private void applyOrder() {
        items.sort((a, b) -> {
            int result;
            switch (sort) {
                case NAME:
                    result = Integer.compare(letterRank(a), letterRank(b));
                    if (result == 0) {
                        result = collator.compare(a.file.getName(), b.file.getName());
                    }
                    break;
                case SIZE:
                    result = Long.compare(a.size, b.size);
                    break;
                case TIME:
                default:
                    result = Long.compare(a.modified, b.modified);
                    break;
            }
            if (result == 0) {
                result = collator.compare(a.file.getName(), b.file.getName());
            }
            return ascending ? result : -result;
        });
        notifyDataSetChanged();
    }

    /** 首字母：A-Z 视为字母组，其余（中文 / 数字 / 符号）归到 #。 */
    static String letterOf(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "#";
        }
        char c = Character.toUpperCase(fileName.charAt(0));
        return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
    }

    private static int letterRank(Item item) {
        String letter = letterOf(item.file.getName());
        return "#".equals(letter) ? 27 : letter.charAt(0) - 'A';
    }

    /** 升序的字母索引（仅包含当前列表中出现的字母）。 */
    List<String> letters() {
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            String letter = letterOf(item.file.getName());
            if (!out.contains(letter)) {
                out.add(letter);
            }
        }
        return out;
    }

    /** 某字母分组在列表中的首个位置，找不到返回 -1。 */
    int indexOfLetter(String letter) {
        for (int i = 0; i < items.size(); i++) {
            if (letterOf(items.get(i).file.getName()).equals(letter)) {
                return i;
            }
        }
        return -1;
    }

    void toggle(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        String path = items.get(position).file.getAbsolutePath();
        if (!selected.remove(path)) {
            selected.add(path);
        }
        notifyItemChanged(position);
        if (listener != null) {
            listener.onToggle(items.get(position));
        }
    }

    void selectAll(boolean select) {
        selected.clear();
        if (select) {
            for (Item item : items) {
                selected.add(item.file.getAbsolutePath());
            }
        }
        notifyDataSetChanged();
    }

    boolean allSelected() {
        return !items.isEmpty() && selected.size() == items.size();
    }

    int selectedCount() {
        return selected.size();
    }

    List<File> selectedFiles() {
        List<File> out = new ArrayList<>();
        for (Item item : items) {
            if (selected.contains(item.file.getAbsolutePath())) {
                out.add(item.file);
            }
        }
        return out;
    }

    List<File> allFiles() {
        List<File> out = new ArrayList<>();
        for (Item item : items) {
            out.add(item.file);
        }
        return out;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audio_file, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Item item = items.get(position);
        holder.name.setText(item.file.getName());
        String size = DownloadStore.formatSize(item.size);
        String time = item.modified > 0 ? dateFormat.format(new Date(item.modified)) : "";
        holder.meta.setText(item.duration == null || item.duration.isEmpty()
                ? holder.itemView.getContext().getString(R.string.download_audio_meta, size, time)
                : holder.itemView.getContext().getString(R.string.download_audio_meta_time,
                size, item.duration, time));
        holder.check.setChecked(selected.contains(item.file.getAbsolutePath()));
        holder.itemView.setOnClickListener(v -> toggle(holder.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCheckBox check;
        final TextView name;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.cb_audio);
            name = itemView.findViewById(R.id.tv_audio_name);
            meta = itemView.findViewById(R.id.tv_audio_meta);
        }
    }
}
