package com.auramusic.wiki.classic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

/**
 * 可拖动的快速滚动条（Expressive 版）。
 *
 * <p>系统滚动条只用来「看」，不能拖动跳到远处；这里自绘一条常驻的细轨 + 胶囊滑块：
 * <ul>
 *   <li>滑块长度按「可视范围 / 总范围」实时变化；</li>
 *   <li>在条上按下或拖动时，按纵坐标比例把列表直接移动到对应位置（不是逐屏滚动）；</li>
 *   <li>内容不足一屏时自动隐藏；</li>
 *   <li>按住时滑块加宽，松手回落，给拖拽一个明确的触感反馈；</li>
 *   <li>列表被拖到顶部 / 底部之后继续拉（Android 12+ 的边缘拉伸）时，
 *       对应一端的滑块会随拉伸量缩短、松手后与系统回弹同时长回来，
 *       让「已经到头了」这件事在滚动条上也能被看到。</li>
 * </ul>
 *
 * <p>触摸热区是整条 24dp 宽的区域，视觉上的滑块只有 6dp，避免不好按。
 */
public class QuickScrollBar extends View {

    private static final float THUMB_WIDTH_DP = 6f;
    /** 按住时滑块加宽到 10dp，松手回到 6dp */
    private static final float THUMB_ACTIVE_WIDTH_DP = 10f;
    private static final float MIN_THUMB_HEIGHT_DP = 28f;
    private static final int TOUCH_WIDTH_DP = 24;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable
    private RecyclerView target;
    private final RecyclerView.OnScrollListener scrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    refresh();
                }
            };

    /** 当前按住加宽量 0..1（平滑跟随 {@link #pressedTarget}） */
    private float pressed;
    /** 超限拉伸量 0..1（平滑跟随 {@link #overscrollTarget}） */
    private float overscroll;
    /** 正在显示的超限方向：-1 顶部、1 底部、0 无 */
    private int overscrollEdge;
    /** 目标加宽量 */
    private float pressedTarget;
    /** 目标拉伸量 */
    private float overscrollTarget;
    /** 目标拉伸方向 */
    private int overscrollTargetEdge;
    /** 手指按下时与列表边缘的距离，用于换算拉伸量 */
    private float pullStartY;
    /** 是否正在做平滑跟随 */
    private boolean smoothing;

    /**
     * 平滑跟随：每帧把当前值向目标值靠近。
     *
     * <p>直接按手指位移赋值会让滑块长度在「到顶 / 离顶」的判定边界来回跳，
     * 表现为超限动画偶发闪动；这里统一走一次限速插值，输入抖动被吸收掉，
     * 松手时目标归零即自然回弹，和系统的边缘拉伸节奏一致。
     */
    private final Runnable smoothRunnable = new Runnable() {
        @Override
        public void run() {
            boolean moving = false;
            float deltaOverscroll = overscrollTarget - overscroll;
            if (Math.abs(deltaOverscroll) > 0.004f) {
                overscroll += deltaOverscroll * 0.22f;
                moving = true;
            } else {
                overscroll = overscrollTarget;
            }
            if (overscroll > 0f && overscrollTargetEdge != 0) {
                overscrollEdge = overscrollTargetEdge;
            }
            float deltaPressed = pressedTarget - pressed;
            if (Math.abs(deltaPressed) > 0.01f) {
                pressed += deltaPressed * 0.3f;
                moving = true;
            } else {
                pressed = pressedTarget;
            }
            invalidate();
            if (moving) {
                ViewCompat.postOnAnimation(QuickScrollBar.this, this);
            } else {
                smoothing = false;
                if (overscroll <= 0f) {
                    overscrollEdge = 0;
                }
            }
        }
    };

    public QuickScrollBar(Context context) {
        this(context, null);
    }

    public QuickScrollBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        // 轨道与滑块都适当降透明度：滚动条只是辅助指示，不应压过列表内容
        trackPaint.setColor(withAlpha(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest), 0.55f));
        thumbPaint.setColor(withAlpha(MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary), 0.82f));
        trackPaint.setStrokeWidth(THUMB_WIDTH_DP * density);
        thumbPaint.setStrokeWidth(THUMB_WIDTH_DP * density);
        setVisibility(GONE);
    }

    private static int withAlpha(int color, float alpha) {
        float clamped = Math.max(0f, Math.min(1f, alpha));
        return (color & 0x00FFFFFF) | (Math.round(255f * clamped) << 24);
    }

    /** 绑定列表：之后滚动与数据变化都会刷新滑块。 */
    void attach(RecyclerView recyclerView) {
        if (target == recyclerView) {
            return;
        }
        if (target != null) {
            target.removeOnScrollListener(scrollListener);
            target.setOnTouchListener(null);
        }
        target = recyclerView;
        if (target != null) {
            target.addOnScrollListener(scrollListener);
            // 数据变化不会触发 onScrolled，挂一层布局监听保证滑块长度同步
            target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> refresh());
            // 只旁路观察手势（返回 false 不消费事件），用来感知边缘拉伸
            target.setOnTouchListener((v, event) -> {
                trackOverscroll(event);
                return false;
            });
        }
        refresh();
    }

    /** 依据当前内容高度决定显隐，并在可滚动时重绘滑块。 */
    private void refresh() {
        RecyclerView view = target;
        boolean scrollable = view != null
                && view.computeVerticalScrollRange() > view.computeVerticalScrollExtent();
        setVisibility(scrollable ? VISIBLE : GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RecyclerView view = target;
        if (view == null || getHeight() <= 0) {
            return;
        }
        int range = view.computeVerticalScrollRange();
        int extent = view.computeVerticalScrollExtent();
        if (range <= extent || extent <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float thumbMin = MIN_THUMB_HEIGHT_DP * density;
        float trackTop = getHeight() * 0.04f;
        float trackBottom = getHeight() * 0.96f;
        float trackHeight = trackBottom - trackTop;
        float thumbHeight = Math.max(thumbMin, trackHeight * extent / range);
        float travel = Math.max(0f, trackHeight - thumbHeight);
        int offset = view.computeVerticalScrollOffset();
        float fraction = Math.max(0f, Math.min(1f, offset / (float) (range - extent)));
        float top = trackTop + travel * fraction;
        float cx = getWidth() / 2f;
        float radius = (THUMB_WIDTH_DP * density) / 2f;
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        thumbPaint.setStrokeCap(Paint.Cap.ROUND);
        // 按住时滑块加宽；超限时再多加一点，和系统的拉伸反馈同向
        float thumbWidth = (THUMB_WIDTH_DP
                + (THUMB_ACTIVE_WIDTH_DP - THUMB_WIDTH_DP) * pressed
                + 2f * overscroll) * density;
        thumbPaint.setStrokeWidth(thumbWidth);
        // 超限拉伸时色条从被拉伸的一端缩短：幅度控制在滑块长度的三成以内，
        // 既看得出「已经到头」，又不会因为一帧里的手感抖动而大幅伸缩。
        float shorten = thumbHeight * 0.3f * overscroll;
        float thumbFrom = top;
        float thumbTo = top + thumbHeight;
        if (overscrollEdge < 0) {
            // 顶部拉伸：滑块贴住轨道顶端，长度从下端收
            thumbTo -= shorten;
        } else if (overscrollEdge > 0) {
            // 底部拉伸：滑块贴住轨道底端，长度从上端收
            thumbFrom += shorten;
        }
        canvas.drawLine(cx, trackTop + radius, cx, trackBottom - radius, trackPaint);
        if (thumbTo - thumbFrom > radius * 2f) {
            canvas.drawLine(cx, thumbFrom + radius, cx, thumbTo - radius, thumbPaint);
        }
    }

    /**
     * 列表是否已经贴住指定边缘（-1 顶部、1 底部）。
     *
     * <p>底部不能只依赖 {@link RecyclerView#canScrollVertically}：它用 ScrollbarHelper
     * 的「平均行高 × 行数」估算总范围，末项刚好铺满视口时窗口平均行高与真实值存在取整差，
     * 会偶发返回「还能滚」，表现为底部超限动画时有时无（顶部不受影响，偏移量为 0 时估算是精确的）。
     * 这里直接看首 / 末项的布局位置，与行高是否均匀无关。
     */
    private boolean atEdge(RecyclerView view, int direction) {
        RecyclerView.LayoutManager manager = view.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) {
            return direction < 0 ? !view.canScrollVertically(-1) : !view.canScrollVertically(1);
        }
        if (direction < 0 && !view.canScrollVertically(-1)) {
            return true;
        }
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int count = linear.getItemCount();
        if (count <= 0) {
            return true;
        }
        View edge = linear.findViewByPosition(direction < 0 ? 0 : count - 1);
        if (edge == null) {
            return false;
        }
        // 1dp 容差吸收像素取整
        int slack = Math.max(1, Math.round(getResources().getDisplayMetrics().density));
        if (direction < 0) {
            return edge.getTop() >= view.getPaddingTop() - slack;
        }
        return edge.getBottom() <= view.getHeight() - view.getPaddingBottom() + slack;
    }

    /**
     * 旁路观察列表手势：到顶后继续下拉 / 到底后继续上拉时，把多出来的距离换算成
     * 0..1 的拉伸量；松手后回弹。
     *
     * <p>返回 false 不消费事件，列表自身的滚动与系统边缘拉伸照常进行。
     */
    private void trackOverscroll(MotionEvent event) {
        RecyclerView view = target;
        if (view == null) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pullStartY = event.getY();
                setPressedTarget(1f);
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - pullStartY;
                float limit = Math.max(1f, getHeight() * 0.3f);
                if (dy > 0 && atEdge(view, -1)) {
                    setOverscrollTarget(-1, Math.min(1f, dy / limit));
                } else if (dy < 0 && atEdge(view, 1)) {
                    setOverscrollTarget(1, Math.min(1f, -dy / limit));
                } else {
                    setOverscrollTarget(0, 0f);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressedTarget(0f);
                setOverscrollTarget(0, 0f);
                break;
            default:
                break;
        }
    }

    private void setOverscrollTarget(int edge, float amount) {
        overscrollTarget = amount;
        overscrollTargetEdge = amount <= 0f ? 0 : edge;
        startSmoothing();
    }

    private void setPressedTarget(float amount) {
        pressedTarget = amount;
        startSmoothing();
    }

    /** 关闭动效时直接跳到目标；否则启动逐帧平滑（同一帧里同时收敛加宽与拉伸）。 */
    private void startSmoothing() {
        if (smoothing) {
            return;
        }
        if (!MotionManager.animationsEnabled(getContext())) {
            overscroll = overscrollTarget;
            pressed = pressedTarget;
            overscrollEdge = overscrollTargetEdge;
            invalidate();
            return;
        }
        smoothing = true;
        ViewCompat.postOnAnimation(this, smoothRunnable);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        RecyclerView view = target;
        if (view == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 拖动滚动条本身也走与超限一致的加粗动画
                setPressedTarget(1f);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                jumpTo(event.getY());
                ViewCompat.postInvalidateOnAnimation(this);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setPressedTarget(0f);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 把触摸纵坐标映射为列表滚动位置。 */
    private void jumpTo(float y) {
        RecyclerView view = target;
        if (view == null || getHeight() <= 0) {
            return;
        }
        int range = view.computeVerticalScrollRange();
        int extent = view.computeVerticalScrollExtent();
        int scrollRange = range - extent;
        if (scrollRange <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float thumbMin = MIN_THUMB_HEIGHT_DP * density;
        float trackTop = getHeight() * 0.04f;
        float trackBottom = getHeight() * 0.96f;
        float trackHeight = trackBottom - trackTop;
        float thumbHeight = Math.max(thumbMin, trackHeight * extent / range);
        float travel = Math.max(1f, trackHeight - thumbHeight);
        float fraction = Math.max(0f, Math.min(1f, (y - trackTop - thumbHeight / 2f) / travel));
        int targetOffset = Math.round(fraction * scrollRange);
        view.scrollBy(0, targetOffset - view.computeVerticalScrollOffset());
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (target != null) {
            target.removeOnScrollListener(scrollListener);
            target.setOnTouchListener(null);
        }
        removeCallbacks(smoothRunnable);
        smoothing = false;
        super.onDetachedFromWindow();
    }
}
