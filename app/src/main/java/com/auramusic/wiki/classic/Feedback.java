package com.auramusic.wiki.classic;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.android.material.snackbar.Snackbar;

/**
 * 应用内统一反馈通道（M3 Snackbar）。
 *
 * <p>替代零散的 {@code Toast}：
 * <ul>
 *   <li>反馈留在应用内，不会浮在系统层；</li>
 *   <li>可以承载「撤销 / 重试」等动作；</li>
 *   <li>主界面会自动抬到悬浮底栏之上，避免被玻璃底栏遮住。</li>
 * </ul>
 *
 * <p>Snackbar 挂在 {@code android.R.id.content} 上，因此与页面切换无关，
 * 即使当前页面正在淡入淡出也不会丢。
 */
final class Feedback {

    /**
     * 最近一次展示的提示。
     *
     * <p>「正在定位…」这类进行中提示在任务完成后需要主动收起，
     * 否则会一直盖在结果上方直到自然超时。
     */
    @Nullable
    private static Snackbar current;

    private Feedback() {
    }

    /** 收起当前提示（没有则忽略）。 */
    static void dismiss() {
        Snackbar bar = current;
        current = null;
        if (bar != null) {
            bar.dismiss();
        }
    }

    /** 短提示（2 秒左右，适合「已保存 / 已复制」这类确认）。 */
    static void show(@NonNull View anchor, @StringRes int res) {
        show(anchor, anchor.getContext().getString(res), Snackbar.LENGTH_SHORT);
    }

    /** 短提示（动态文案）。 */
    static void show(@NonNull View anchor, CharSequence text) {
        show(anchor, text, Snackbar.LENGTH_SHORT);
    }

    /** 长提示（需要读完的错误信息）。 */
    static void showLong(@NonNull View anchor, CharSequence text) {
        show(anchor, text, Snackbar.LENGTH_LONG);
    }

    /** 以 Activity 的 content 视图为锚点的短提示。 */
    static void show(@NonNull android.app.Activity activity, @StringRes int res) {
        show(rootOf(activity), activity.getString(res), Snackbar.LENGTH_SHORT);
    }

    /** 以 Activity 的 content 视图为锚点的短提示（动态文案）。 */
    static void show(@NonNull android.app.Activity activity, CharSequence text) {
        show(rootOf(activity), text, Snackbar.LENGTH_SHORT);
    }

    /** 以 Activity 的 content 视图为锚点的长提示。 */
    static void showLong(@NonNull android.app.Activity activity, CharSequence text) {
        show(rootOf(activity), text, Snackbar.LENGTH_LONG);
    }

    private static View rootOf(android.app.Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        return content != null ? content : activity.getWindow().getDecorView();
    }

    /** 带动作的长提示（撤销 / 重试）。 */
    static void action(@NonNull View anchor, @StringRes int res,
                       @StringRes int actionRes, @NonNull Runnable action) {
        Snackbar bar = build(anchor, anchor.getContext().getString(res), Snackbar.LENGTH_LONG);
        bar.setAction(actionRes, v -> action.run());
        bar.show();
    }

    private static void show(View anchor, CharSequence text, int duration) {
        build(anchor, text, duration).show();
    }

    private static Snackbar build(View anchor, CharSequence text, int duration) {
        Snackbar bar = Snackbar.make(suitableParent(anchor), text, duration);
        liftAboveBottomOverlay(bar, anchor);
        bar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar dismissed, int event) {
                if (current == dismissed) {
                    current = null;
                }
            }
        });
        current = bar;
        return bar;
    }

    /**
     * Snackbar 需要挂在常驻的容器上：优先用 Activity 的 content 视图，
     * 这样提示不会随某个页面的显隐一起消失。
     */
    private static View suitableParent(View anchor) {
        View content = anchor.getRootView().findViewById(android.R.id.content);
        return content != null ? content : anchor;
    }

    /**
     * 主界面有悬浮底栏（bottom_overlay）时，把 Snackbar 抬高一个底栏的高度，
     * 否则提示会被玻璃胶囊盖住一半。
     */
    private static void liftAboveBottomOverlay(Snackbar bar, View anchor) {
        View overlay = findBottomOverlay(anchor);
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) {
            return;
        }
        View snackbarView = bar.getView();
        if (overlay.getHeight() > 0) {
            applyLift(snackbarView, overlay.getHeight());
            return;
        }
        // 启动首帧（例如「已恢复上次查询」）时底栏还没测量完，高度是 0：
        // 等它完成一次布局再抬，否则提示会直接压在底栏上
        overlay.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        overlay.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        applyLift(snackbarView, overlay.getHeight());
                    }
                });
    }

    private static void applyLift(View view, int overlayHeight) {
        if (overlayHeight <= 0) {
            return;
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        int gap = Math.round(8f * view.getResources().getDisplayMetrics().density);
        flp.bottomMargin += overlayHeight + gap;
        view.setLayoutParams(flp);
    }

    @Nullable
    private static View findBottomOverlay(View anchor) {
        try {
            return anchor.getRootView().findViewById(R.id.bottom_overlay);
        } catch (Exception e) {
            return null;
        }
    }
}
