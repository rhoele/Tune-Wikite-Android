package com.auramusic.wiki.classic;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.motion.MotionUtils;

/**
 * 动效规格与播放工具（第二阶段：控件体验 / 导航栏 / 页面转场）。
 *
 * <p>全部时长与插值曲线取自 Material 3 的 motion token（{@code motionDuration*} /
 * {@code motionEasing*}），也就是 Google 应用与系统动画使用的那套曲线，
 * 因此观感与原生一致；再由用户的「动效强度」统一缩放：
 * <ul>
 *   <li>标准 = 1.0 倍（Material 官方时长）；</li>
 *   <li>强调 = 1.4 倍时长 + 放大的位移；</li>
 *   <li>关闭 = 直接呈现终态，不做任何过渡。</li>
 * </ul>
 *
 * <p>模糊基于 {@link RenderEffect}（Android 12+），用于页面层级切换时让离场内容
 * 在淡出过程中被真实地硬件模糊，而不是叠一层半透明纯色。
 */
public final class MotionManager {

    /**
     * 离场模糊半径上限（dp）
     */
    private static final float BLUR_RADIUS_DP = 16f;

    private static final TimeInterpolator FALLBACK = new FastOutSlowInInterpolator();

    /** 数字计数器的基础时长（毫秒）：比控件反馈略长，读数过程才看得清 */
    private static final int COUNT_UP_BASE_MS = 520;

    private MotionManager() {
    }

    // ------------------------------------------------------------------
    // 时长与曲线
    // ------------------------------------------------------------------

    public static boolean animationsEnabled(Context context) {
        return AppearanceManager.motionsEnabled(context);
    }

    /** 目的地切换时长（毫秒），已按用户动效强度缩放。 */
    public static long destinationDuration(Context context) {
        return duration(context, context.getResources().getInteger(R.integer.motion_destination));
    }

    /** 控件级过渡时长（毫秒），已按用户动效强度缩放。 */
    public static long controlDuration(Context context) {
        return duration(context, context.getResources().getInteger(R.integer.motion_control));
    }

    /**
     * 基准时长按动效强度缩放；关闭动效时返回 0。
     *
     * <p>不再额外乘系统的「动画时长缩放」——{@link android.animation.ValueAnimator}
     * 自身已经会尊重开发者选项里的动画缩放，手动乘一次反而会重复缩放。
     */
    public static long duration(Context context, int baseMs) {
        float scale = AppearanceManager.motionScale(context);
        if (scale <= AppearanceManager.MOTION_OFF + 0.01f) {
            return 0L;
        }
        return Math.max(1L, Math.round(baseMs * scale));
    }

    /** 位移量按动效强度缩放（标准 1.0、强调更大）。 */
    public static float travel(Context context, float baseDp) {
        float scale = AppearanceManager.motionScale(context);
        if (scale <= AppearanceManager.MOTION_OFF + 0.01f) {
            return 0f;
        }
        return baseDp * scale;
    }

    /** Material 3 「标准」缓动曲线（进入 / 位移类动画）。 */
    public static TimeInterpolator standard(Context context) {
        return resolveInterpolator(context,
                com.google.android.material.R.attr.motionEasingStandardInterpolator);
    }

    /** Material 3 「强调」缓动曲线（层级切换、较大位移的动画）。 */
    public static TimeInterpolator emphasized(Context context) {
        return resolveInterpolator(context,
                com.google.android.material.R.attr.motionEasingEmphasizedInterpolator);
    }

    private static TimeInterpolator resolveInterpolator(Context context, int attr) {
        try {
            return MotionUtils.resolveThemeInterpolator(context, attr, FALLBACK);
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    /** 数字计数器的进度回调：{@code fraction} 从 0 推进到 1。 */
    public interface CounterListener {
        void onFraction(float fraction);
    }

    /**
     * 数字计数器：按用户动效强度与「强调」曲线推进 0 → 1 的进度，
     * 调用方自行把进度插值到各数值后再格式化（同一行含多个数字时可共用一次动画）。
     *
     * @return 正在播放的动画；关闭动效时时长为 0，立即回调 1f 并返回 null
     */
    @Nullable
    public static ValueAnimator counter(Context context, @Nullable CounterListener listener) {
        if (listener == null) {
            return null;
        }
        long duration = duration(context, COUNT_UP_BASE_MS);
        if (duration <= 0L) {
            listener.onFraction(1f);
            return null;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(emphasized(context));
        animator.addUpdateListener(a -> listener.onFraction((float) a.getAnimatedValue()));
        animator.start();
        return animator;
    }

    // ------------------------------------------------------------------
    // 模糊（Android 12+ 硬件模糊）
    // ------------------------------------------------------------------

    /** 是否应当对离场内容施加模糊：用户开关 + 设备支持 + 动效未关闭。 */
    public static boolean blurActive(Context context) {
        return AppearanceManager.blurEnabled(context)
                && AppearanceManager.blurSupported()
                && animationsEnabled(context);
    }

    /** 对视图施加一层硬件模糊；不支持时静默跳过。 */
    public static void applyBlur(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            float radius = view.getResources().getDisplayMetrics().density * BLUR_RADIUS_DP;
            view.setRenderEffect(RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP));
        } catch (Throwable ignored) {
            // 个别 ROM 对 RenderEffect 支持不完整，降级为普通淡出
        }
    }

    /** 清除模糊，恢复原始渲染。 */
    public static void clearBlur(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            view.setRenderEffect(null);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    /**
     * 目的地 / 页面层级切换：离场内容淡出（可叠加模糊），入场内容淡入并轻微上浮。
     * 关闭动效时直接交换可见性。
     *
     * <p>离场视图统一置为 {@link View#INVISIBLE} 而非 {@code GONE}：不可见但仍参与测量与
     * 布局，因此下一次切回该目的地时无需重新测量整棵内容树，切换耗时稳定在 100ms 以内，
     * 也不会出现首帧空窗（白屏）。
     */
    public static void crossFade(Context context, final View outgoing, final View incoming,
                                 boolean incomingFromBelow) {
        if (outgoing == null || incoming == null) {
            return;
        }
        long duration = destinationDuration(context);
        if (duration <= 0L) {
            outgoing.setVisibility(View.INVISIBLE);
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(1f);
            incoming.setTranslationY(0f);
            clearBlur(outgoing);
            return;
        }

        final boolean blur = blurActive(context);
        float travel = travel(context, incomingFromBelow ? 12f : 0f);

        // 离场：先模糊再淡出，结束后复位，避免残留 RenderEffect 影响后续渲染
        if (blur) {
            applyBlur(outgoing);
        }
        outgoing.animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(emphasized(context))
                .withEndAction(() -> {
                    outgoing.setVisibility(View.INVISIBLE);
                    outgoing.setAlpha(1f);
                    clearBlur(outgoing);
                })
                .start();

        // 入场
        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.setTranslationY(travel);
        incoming.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration)
                .setInterpolator(emphasized(context))
                .start();
    }

    /**
     * 自下边界滑入（悬浮附属控件，如页面选择器）：位移量随动效强度缩放，
     * 时长与曲线取控件级过渡规格，与底栏等其它控件的过渡完全一致。
     */
    public static void slideInFromBottom(Context context, final View view) {
        slideInFromBottom(context, view, null);
    }

    /** 同上，动画结束后回调（用于同步形状 / 模糊等派生状态） */
    public static void slideInFromBottom(Context context, final View view,
                                         @Nullable final Runnable onEnd) {
        slideFromBottom(context, view, true, onEnd);
    }

    /** 向下滑出（悬浮附属控件退场） */
    public static void slideOutToBottom(Context context, final View view) {
        slideOutToBottom(context, view, null);
    }

    /** 同上，动画结束后回调（用于同步形状 / 模糊等派生状态） */
    public static void slideOutToBottom(Context context, final View view,
                                        @Nullable final Runnable onEnd) {
        slideFromBottom(context, view, false, onEnd);
    }

    /**
     * 悬浮附属控件的上下滑入 / 滑出。
     *
     * <p>退场结束后统一置为 {@link View#GONE}（而非 {@code INVISIBLE}）：这类控件位于
     * 悬浮覆盖层内部，占据的高度会直接撑高覆盖层；若不释放，页面选择器收起后覆盖层
     * 仍留出一条空白模糊带，并让滚动内容多出一段不必要的底部留白。
     * （页面级切换保留 {@code INVISIBLE}，那是为了避免整棵内容树重新测量，见 {@link #crossFade}。）
     */
    private static void slideFromBottom(Context context, final View view, boolean enter,
                                        @Nullable final Runnable onEnd) {
        if (view == null) {
            return;
        }
        // 进场与退场严格互为逆过程：同一 travel、同一 duration、同一 M3 standard 曲线。
        // 退场不再把状态强制重置到 (0, 1) 再播动画 —— 那会在动画被中途打断时产生一次跳变；
        // 直接从当前状态插值到目标状态，才能保证任何时刻打断都连续。
        float travel = travel(context, 16f);
        long duration = controlDuration(context);
        view.animate().cancel();
        if (duration <= 0L) {
            view.setTranslationY(0f);
            view.setAlpha(1f);
            view.setVisibility(enter ? View.VISIBLE : View.GONE);
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        if (enter) {
            // 退场被打断（视图仍可见且未完全透明）时，从当前状态继续，避免跳回起点造成跳变；
            // 正常入场则固定从「下方 travel + 透明」起步，保证每次进入轨迹一致。
            boolean interruptedExit = view.getVisibility() == View.VISIBLE
                    && view.getAlpha() > 0.01f;
            view.setVisibility(View.VISIBLE);
            if (!interruptedExit) {
                view.setTranslationY(travel);
                view.setAlpha(0f);
            }
            view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .withLayer()
                    .setDuration(duration)
                    .setInterpolator(standard(context))
                    .withEndAction(() -> {
                        if (onEnd != null) {
                            onEnd.run();
                        }
                    })
                    .start();
        } else {
            if (view.getVisibility() != View.VISIBLE) {
                view.setVisibility(View.VISIBLE);
            }
            view.animate()
                    .translationY(travel)
                    .alpha(0f)
                    .withLayer()
                    .setDuration(duration)
                    .setInterpolator(standard(context))
                    .withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setTranslationY(0f);
                        view.setAlpha(1f);
                        if (onEnd != null) {
                            onEnd.run();
                        }
                    })
                    .start();
        }
    }

    // ------------------------------------------------------------------
    // 分页切换
    // ------------------------------------------------------------------

    /**
     * 分页请求发出：列表轻微压暗，作为「正在换页」的即时反馈。
     *
     * <p>与主流分页组件一致：换页采用短促的淡入淡出，页码立即更新、内容不位移，
     * 既不打断用户对列表位置的记忆，也不产生整页闪动。
     */
    public static void dimList(Context context, final View view) {
        if (view == null) {
            return;
        }
        long duration = duration(context, 90);
        if (duration <= 0L) {
            return;
        }
        view.animate().cancel();
        view.animate()
                .alpha(0.55f)
                .setDuration(duration)
                .setInterpolator(standard(context))
                .start();
    }

    /** 新页渲染完成（或失败回退）：恢复不透明 */
    public static void undimList(Context context, final View view) {
        if (view == null) {
            return;
        }
        long duration = duration(context, 160);
        view.animate().cancel();
        if (duration <= 0L) {
            view.setAlpha(1f);
            return;
        }
        view.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(standard(context))
                .start();
    }

    /**
     * 本地换页（检索命中集切片）：先淡出，再应用新数据，最后淡入。
     *
     * <p>应用动作从「当前状态」读取数据，因此连续快速翻页时即使中间某次动画被打断，
     * 最终呈现的仍是最后一次请求的页，不会出现半更新或错位。
     */
    public static void pageSwap(Context context, final View view, final Runnable apply) {
        if (apply == null) {
            return;
        }
        if (view == null) {
            apply.run();
            return;
        }
        long out = duration(context, 90);
        long in = duration(context, 160);
        if (out <= 0L || in <= 0L) {
            apply.run();
            return;
        }
        view.animate().cancel();
        view.animate()
                .alpha(0.45f)
                .setDuration(out)
                .setInterpolator(standard(context))
                .withEndAction(() -> {
                    apply.run();
                    view.animate()
                            .alpha(1f)
                            .setDuration(in)
                            .setInterpolator(standard(context))
                            .start();
                })
                .start();
    }

    // ------------------------------------------------------------------
    // 控件级动效（对应 Compose 的 AnimatedVisibility / animateContentSize）
    // ------------------------------------------------------------------

    /**
     * 内容淡入上浮：用于「解析完成」「封面就绪」这类结果区出现。
     * 与 AnimatedVisibility(enter = fadeIn + slideInVertically) 的观感一致。
     */
    public static void fadeSlideIn(Context context, final View view) {
        if (view == null) {
            return;
        }
        long duration = duration(context, 200);
        if (duration <= 0L) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(travel(context, 8f));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration)
                .setInterpolator(emphasized(context))
                .start();
    }

    /**
     * 控件显隐的平滑过渡（AnimatedVisibility 等价物）：
     * 进入淡入 + 96% 缩放到 100%，退出淡出 + 轻微缩小；关闭动效时直接切换。
     */
    public static void animateVisibility(Context context, final View view, boolean visible) {
        if (view == null) {
            return;
        }
        long duration = duration(context, 180);
        view.animate().cancel();
        if (duration <= 0L) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        if (visible) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setScaleX(0.96f);
                view.setScaleY(0.96f);
            }
            view.setVisibility(View.VISIBLE);
            view.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(standard(context))
                    .start();
        } else {
            if (view.getVisibility() != View.VISIBLE) {
                return;
            }
            view.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(duration)
                    .setInterpolator(standard(context))
                    .withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                    })
                    .start();
        }
    }

    /**
     * 容器内容变化时的自动过渡（animateContentSize 等价物）：
     * 在增删子控件前调用，让高度与透明度平滑变化，而不是瞬间跳动。
     */
    public static void beginAutoTransition(Context context, android.view.ViewGroup group) {
        if (group == null || !animationsEnabled(context)) {
            return;
        }
        try {
            androidx.transition.TransitionSet set = new androidx.transition.TransitionSet()
                    .addTransition(new androidx.transition.ChangeBounds())
                    .addTransition(new androidx.transition.Fade());
            set.setDuration(duration(context, 200));
            set.setInterpolator(standard(context));
            androidx.transition.TransitionManager.beginDelayedTransition(group, set);
        } catch (Throwable ignored) {
            // 过渡库不可用时直接更新
        }
    }

    /** 图片内容切换时的淡入（封面加载完成）。 */
    public static void fadeInImage(Context context, final View imageView) {
        if (imageView == null) {
            return;
        }
        long duration = duration(context, 220);
        imageView.animate().cancel();
        if (duration <= 0L) {
            imageView.setAlpha(1f);
            return;
        }
        imageView.setAlpha(0f);
        imageView.animate()
                .alpha(1f)
                .setDuration(duration)
                .setInterpolator(standard(context))
                .start();
    }
}
