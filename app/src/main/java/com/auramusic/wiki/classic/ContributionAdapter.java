package com.auramusic.wiki.classic;

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * 「我的贡献」列表适配器（补全 G1：原先 renderList() 为空实现）。
 *
 * 特性：
 *   - 基于 {@link ListAdapter} + DiffUtil，增量更新不闪烁
 *   - 状态徽标颜色取自 MD3 语义色令牌，深浅色主题自动适配
 *   - 封面走 {@link CoverLoader}，带内存缓存与域名白名单
 *   - 时间格式化安全兜底，0 值显示「时间未知」
 */
public class ContributionAdapter extends ListAdapter<Contribution, ContributionAdapter.VH> {

    private static final String TAG = "ContributionAdapter";

    public interface OnItemClick {
        void onItemClick(Contribution item);
    }

    /** 长按条目（第二阶段：长按场景的触感与行为入口） */
    public interface OnItemLongClick {
        void onItemLongClick(Contribution item);
    }

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private final OnItemClick clickListener;

    /** 长按回调，未设置时不响应长按 */
    private OnItemLongClick longClickListener;

    /** 主线程消息队列，用于把刷新调度到当前布局/滚动周期之外 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 用户开关（设置 → 显示 → 显示贡献卡片封面） */
    private boolean showCovers = true;
    /** 本页条目本身没有可用封面（歌词 / 翻译等）时全局隐藏图标 */
    private boolean coversUnavailable = false;

    /** 未读审核结果标记：命中 stableKey 的卡片展示「新」徽标 */
    private Set<String> unreadKeys = Collections.emptySet();

    public ContributionAdapter(OnItemClick clickListener) {
        super(DIFF);
        this.clickListener = clickListener;
    }

    public boolean isShowCovers() {
        return showCovers;
    }

    /** 设置是否展示封面；关闭后卡片不再请求封面也不显示图标 */
    public void setShowCovers(boolean show) {
        if (this.showCovers != show) {
            this.showCovers = show;
            notifyChangedSafely();
        }
    }

    /** 标记「本页条目没有可用封面」，置位后全局隐藏卡片图标 */
    public void setCoversUnavailable(boolean unavailable) {
        if (this.coversUnavailable != unavailable) {
            this.coversUnavailable = unavailable;
            notifyChangedSafely();
        }
    }

    /**
     * 刷新调度：{@code notifyDataSetChanged()} 若发生在 RecyclerView 计算布局 /
     * 滚动期间会抛 {@code IllegalStateException: Cannot call this method while
     * RecyclerView is computing a layout or scrolling}。此处统一切回主线程消息队列，
     * 保证刷新一定在本次布局/滚动周期结束后执行，从根本上消除该闪退。
     */
    private void notifyChangedSafely() {
        if (!mainHandler.post(this::notifyDataSetChangedInternal)) {
            // post 失败（极端情况，如 Looper 退出）时退化为直接刷新
            notifyDataSetChangedInternal();
        }
    }

    private void notifyDataSetChangedInternal() {
        try {
            if (isComputingLayout()) {
                // 仍处于布局计算中，再次延后，避免抛异常
                mainHandler.post(this::notifyDataSetChangedInternal);
                return;
            }
            notifyDataSetChanged();
        } catch (Exception e) {
            android.util.Log.w(TAG, "列表刷新失败，已忽略", e);
        }
    }

    private boolean isComputingLayout() {
        RecyclerView rv = attachedRecyclerView;
        try {
            return rv != null && rv.isComputingLayout();
        } catch (Exception e) {
            return false;
        }
    }

    private RecyclerView attachedRecyclerView;

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
    }

    /** 更新未读集合；仅在集合真的变化时刷新，避免每次渲染都多一轮全量重绑。 */
    public void setUnreadKeys(Set<String> keys) {
        Set<String> next = keys == null ? Collections.emptySet() : keys;
        if (next.equals(this.unreadKeys)) {
            return;
        }
        this.unreadKeys = next;
        notifyChangedSafely();
    }

    public void setLongClickListener(OnItemLongClick listener) {
        this.longClickListener = listener;
    }

    private static final DiffUtil.ItemCallback<Contribution> DIFF =
            new DiffUtil.ItemCallback<Contribution>() {
                @Override
                public boolean areItemsTheSame(@NonNull Contribution a, @NonNull Contribution b) {
                    // 必须用 stableKey 而不是 bizId：歌词与翻译同属一首歌曲、bizId 相同，
                    // 用 bizId 判等会让两者互相覆盖，表现为「歌词 / 翻译不显示」。
                    return a.stableKey().equals(b.stableKey());
                }

                @Override
                public boolean areContentsTheSame(@NonNull Contribution a, @NonNull Contribution b) {
                    return a.status == b.status
                            && a.routeType() == b.routeType()
                            && a.displayName().equals(b.displayName())
                            && a.displayArtist().equals(b.displayArtist())
                            && TextUtils.equals(a.coverUrl, b.coverUrl)
                            && a.createTime == b.createTime;
                }
            };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contribution, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position), clickListener, this);
    }

    static class VH extends RecyclerView.ViewHolder {

        private final MaterialCardView card;
        private final ImageView ivCover;
        private final TextView tvName;
        private final TextView tvArtist;
        private final TextView tvType;
        private final TextView tvStatus;
        private final TextView tvNew;
        private final TextView tvTime;

        VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_root);
            ivCover = itemView.findViewById(R.id.iv_cover);
            tvName = itemView.findViewById(R.id.tv_name);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            tvType = itemView.findViewById(R.id.tv_type);
            tvStatus = itemView.findViewById(R.id.tv_status);
            tvNew = itemView.findViewById(R.id.tv_new);
            tvTime = itemView.findViewById(R.id.tv_time);
        }

        void bind(final Contribution item, final OnItemClick listener,
                  final ContributionAdapter adapter) {
            if (item == null) {
                return;
            }
            // 单条目渲染异常不得中断整页渲染，更不能冒泡成闪退
            try {
                tvName.setText(item.displayName());

                String artist = item.displayArtist();
                tvArtist.setText(artist);
                tvArtist.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);

                bindType(item.routeType());
                bindStatus(item.statusEnum());
                boolean unread = adapter != null
                        && adapter.unreadKeys.contains(item.stableKey());
                bindNew(unread);
                bindUnreadStroke(unread);
                tvTime.setText(formatTime(item.createTime));

                bindCover(item, adapter);

                itemView.setOnClickListener(v -> {
                    HapticsManager.tap(v);
                    if (listener != null) {
                        listener.onItemClick(item);
                    }
                });

                itemView.setOnLongClickListener(v -> {
                    if (adapter == null || adapter.longClickListener == null) {
                        return false;
                    }
                    HapticsManager.longPress(v);
                    adapter.longClickListener.onItemLongClick(item);
                    return true;
                });
            } catch (Exception e) {
                android.util.Log.e(TAG, "条目渲染失败，已降级为默认样式", e);
            }
        }

        /**
         * 封面显示策略（需求 4）：
         *   - 用户关闭开关、或本页条目本身没有封面 → 全局隐藏图标；
         *   - 否则先占位默认图标，封面读取成功后替换为封面图，失败保留默认图标。
         */
        private void bindCover(final Contribution item, final ContributionAdapter adapter) {
            if (adapter == null || !adapter.showCovers || adapter.coversUnavailable) {
                ivCover.setTag(null);
                ivCover.setImageDrawable(null);
                ivCover.setVisibility(View.GONE);
                return;
            }
            ivCover.setVisibility(View.VISIBLE);
            // 解码边长必须与布局中的实际尺寸一致：目标过大既多解出数倍像素，
            // 又会让「URL + 边长」组成的内存缓存键与预热时错开，使预热完全失效。
            int size = itemView.getResources().getDimensionPixelSize(R.dimen.list_cover_size);
            // 同一条目重绑（例如强调结束后刷新）时不要先退回占位图，
            // 否则封面会「闪一下再回来」，看起来像加载很慢。
            if (item.coverUrl == null || !item.coverUrl.equals(ivCover.getTag())) {
                ivCover.setImageResource(R.drawable.ic_album_muted);
            }
            if (item.coverUrl != null && CoverLoader.isAllowed(item.coverUrl)) {
                CoverLoader.get().load(ivCover, item.coverUrl, size);
            } else {
                ivCover.setTag(null);
            }
        }

        /**
         * 类型标签（需求 1）：区分「全部」标签页里混排的歌曲 / 专辑 / 艺人 / 歌词 / 翻译。
         *
         * <p>歌词与翻译条目的 {@code type} 字段恒为 0 且名称与所属歌曲相同，只有类型标签
         * 才能让两者在视觉上可分辨。未收录的类型（如服务端未文档化的 4 / 103）不展示标签。
         */
        private void bindType(int type) {
            int res = NetEaseWikiClient.typeLabelRes(type);
            if (res == 0) {
                tvType.setVisibility(View.GONE);
                return;
            }
            tvType.setVisibility(View.VISIBLE);
            tvType.setText(res);
            int bg = MaterialColors.getColor(tvType,
                    com.google.android.material.R.attr.colorSurfaceContainerHighest);
            int fg = MaterialColors.getColor(tvType,
                    com.google.android.material.R.attr.colorOnSurfaceVariant);
            tvType.setBackgroundTintList(ColorStateList.valueOf(bg));
            tvType.setTextColor(fg);
        }

        private void bindStatus(Contribution.Status status) {
            int textRes;
            int bgAttr;
            int fgAttr;
            switch (status) {
                case APPROVED:
                    textRes = R.string.status_approved;
                    bgAttr = com.google.android.material.R.attr.colorTertiaryContainer;
                    fgAttr = com.google.android.material.R.attr.colorOnTertiaryContainer;
                    break;
                case PARTIAL:
                    textRes = R.string.status_partial;
                    bgAttr = com.google.android.material.R.attr.colorPrimaryContainer;
                    fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer;
                    break;
                case REJECTED:
                    textRes = R.string.status_rejected;
                    bgAttr = com.google.android.material.R.attr.colorErrorContainer;
                    fgAttr = com.google.android.material.R.attr.colorOnErrorContainer;
                    break;
                case PENDING:
                    textRes = R.string.status_pending;
                    bgAttr = com.google.android.material.R.attr.colorSecondaryContainer;
                    fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
                    break;
                case AUDITING:
                    textRes = R.string.status_auditing;
                    bgAttr = com.google.android.material.R.attr.colorSecondaryContainer;
                    fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer;
                    break;
                default:
                    textRes = R.string.status_unknown;
                    bgAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest;
                    fgAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
                    break;
            }
            tvStatus.setText(textRes);
            int bg = MaterialColors.getColor(tvStatus, bgAttr);
            int fg = MaterialColors.getColor(tvStatus, fgAttr);
            tvStatus.setBackgroundTintList(ColorStateList.valueOf(bg));
            tvStatus.setTextColor(fg);
        }

        private void bindNew(boolean unread) {
            tvNew.setVisibility(unread ? View.VISIBLE : View.GONE);
            if (!unread) {
                return;
            }
            int bg = MaterialColors.getColor(tvNew,
                    com.google.android.material.R.attr.colorTertiaryContainer);
            int fg = MaterialColors.getColor(tvNew,
                    com.google.android.material.R.attr.colorOnTertiaryContainer);
            tvNew.setBackgroundTintList(ColorStateList.valueOf(bg));
            tvNew.setTextColor(fg);
        }

        /** 未读卡片加一圈描边，与「新」标签共同提高辨识度。 */
        private void bindUnreadStroke(boolean unread) {
            if (card == null) {
                return;
            }
            if (!unread) {
                card.setStrokeWidth(0);
                return;
            }
            card.setStrokeWidth(itemView.getResources()
                    .getDimensionPixelSize(R.dimen.stroke_emphasis));
            card.setStrokeColor(MaterialColors.getColor(card,
                    com.google.android.material.R.attr.colorTertiary));
        }

        private String formatTime(long millis) {
            if (millis <= 0L) {
                return itemView.getContext().getString(R.string.unknown_time);
            }
            // 兼容秒级时间戳
            long ms = millis < 100000000000L ? millis * 1000L : millis;
            try {
                return TIME_FORMAT.format(new Date(ms));
            } catch (Exception e) {
                return itemView.getContext().getString(R.string.unknown_time);
            }
        }
    }
}
