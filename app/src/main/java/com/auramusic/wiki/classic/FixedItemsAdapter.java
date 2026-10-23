package com.auramusic.wiki.classic;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

/**
 * 固定候选列表的下拉适配器。
 *
 * <p>{@code AutoCompleteTextView} 在恢复文本（旋转屏幕、进程重建、主题切换等）时会用
 * 当前文本过滤适配器；默认 {@link ArrayAdapter} 会把候选裁剪到只剩与当前值匹配的一项，
 * 表现为「下拉里只有一个选项」。这里让过滤器忽略输入，始终返回完整候选。
 */
final class FixedItemsAdapter extends ArrayAdapter<String> {

    private final String[] items;
    private final Filter filter;

    FixedItemsAdapter(Context context, String[] items) {
        super(context, android.R.layout.simple_list_item_1, items.clone());
        this.items = items.clone();
        this.filter = new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = FixedItemsAdapter.this.items;
                results.count = FixedItemsAdapter.this.items.length;
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                // 候选本身就是全量，过滤结果不替换数据源
                notifyDataSetChanged();
            }
        };
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return filter;
    }
}
