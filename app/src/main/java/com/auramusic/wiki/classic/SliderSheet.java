package com.auramusic.wiki.classic;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDragHandleView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

/**
 * Expressive 数值选择面板：大号当前值 + 可拖动的滑杆。
 *
 * <p>用于「存储用量阈值」「定时清理」的二级界面。拖动时实时回调，
 * 松手即可生效，不需要额外的确认按钮；底部「完成」只负责收起面板。
 */
final class SliderSheet {

    /** 数值格式化：既用于大号当前值，也用于滑杆上的气泡标签。 */
    interface Formatter {
        CharSequence format(float value);
    }

    /** 拖动过程中的实时回调。 */
    interface Listener {
        void onValue(float value);
    }

    private SliderSheet() {
    }

    /**
     * @param from     最小值
     * @param to       最大值
     * @param step     步长（&gt; 0）
     * @param value    初始值，自动钳制到 [from, to] 并对齐到步长
     */
    static void show(Context context, CharSequence title, @Nullable CharSequence supporting,
                     float from, float to, float step, float value,
                     Formatter formatter, Listener listener) {
        show(context, title, supporting, from, to, step, value, formatter, listener, null);
    }

    /**
     * @param onDismiss 面板收起后的收尾动作（例如触发一次清理条件检查）
     */
    static void show(Context context, CharSequence title, @Nullable CharSequence supporting,
                     float from, float to, float step, float value,
                     Formatter formatter, Listener listener, @Nullable Runnable onDismiss) {
        if (context == null || formatter == null) {
            return;
        }
        final float safeStep = step > 0f ? step : 1f;
        final float clamped = clampToStep(value, from, to, safeStep);

        BottomSheetDialog dialog = new BottomSheetDialog(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int hPad = dp(context, 24);
        root.setPadding(hPad, dp(context, 4), hPad, dp(context, 20));

        BottomSheetDragHandleView handle = new BottomSheetDragHandleView(context);
        root.addView(handle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleLarge);
        titleView.setTextColor(MaterialColors.getColor(titleView,
                com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(context, 8);
        root.addView(titleView, titleLp);

        if (supporting != null && supporting.length() > 0) {
            TextView desc = new TextView(context);
            desc.setText(supporting);
            desc.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyMedium);
            desc.setTextColor(MaterialColors.getColor(desc,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = dp(context, 4);
            root.addView(desc, descLp);
        }

        final TextView valueView = new TextView(context);
        valueView.setText(formatter.format(clamped));
        valueView.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_DisplaySmall);
        valueView.setTextColor(MaterialColors.getColor(valueView,
                androidx.appcompat.R.attr.colorPrimary));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.topMargin = dp(context, 16);
        root.addView(valueView, valueLp);

        final Slider slider = new Slider(context);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(safeStep);
        slider.setValue(clamped);
        slider.setLabelBehavior(LabelFormatter.LABEL_VISIBLE);
        slider.setLabelFormatter(v -> String.valueOf(formatter.format(v)));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = dp(context, 8);
        root.addView(slider, sliderLp);
        slider.addOnChangeListener((s, v, fromUser) -> {
            valueView.setText(formatter.format(v));
            if (fromUser && listener != null) {
                listener.onValue(v);
            }
        });

        MaterialButton done = new MaterialButton(context);
        done.setText(R.string.download_slider_done);
        done.setMinHeight(dp(context, 48));
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneLp.topMargin = dp(context, 16);
        done.setOnClickListener(v -> {
            HapticsManager.confirm(v);
            dialog.dismiss();
        });
        root.addView(done, doneLp);

        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        dialog.setContentView(root);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    private static float clampToStep(float value, float from, float to, float step) {
        float v = Math.max(from, Math.min(to, value));
        float steps = Math.round((v - from) / step);
        return Math.max(from, Math.min(to, from + steps * step));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
