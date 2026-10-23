package com.auramusic.wiki.classic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 底栏「亚克力 / 液态玻璃」背景模糊层。
 *
 * <p>Android 没有提供「模糊某个 View <b>背后</b>内容」的公开 API
 * （{@code RenderEffect} 模糊的是视图自身的内容，{@code Window#setBackgroundBlurRadius}
 * 模糊的是窗口背后的壁纸）。因此这里采用业界通行的自绘方案：
 * <ol>
 *   <li>把底栏背后的内容源视图（{@code source}）整体绘制到一张<b>降采样</b>位图上
 *       （默认 1/6 边长，内存开销极小）；</li>
 *   <li>对这张小位图做两到三轮盒式模糊（盒式模糊近似高斯，视觉上足够柔和）；</li>
 *   <li>绘制时按自身在源视图中的位置裁出对应区域、放大铺满，并叠加一层
 *       MD3 表面色蒙版，得到「亚克力 / 液态玻璃」的通透观感。</li>
 * </ol>
 *
 * <p>性能约束：模糊半径按降采样比例折算，因此小位图上的邻居采样即可覆盖大范围；
 * 滚动过程中的刷新由 {@link #markDirty()} 节流（默认 60ms 一次），避免每帧重建位图。
 *
 * <p>降级：{@link #STYLE_NONE} 时本视图不绘制任何内容，底栏由不透明表面色承载，
 * 因此任何机型、任何系统版本都不会出现「模糊不可用导致看不清」的问题。
 */
public class BackdropBlurView extends View {

    /** 不开启模糊：底栏改用不透明表面色 */
    public static final int STYLE_NONE = 0;
    /** 亚克力：中等模糊 + 较高不透明度，接近系统「亚克力」材质 */
    public static final int STYLE_ACRYLIC = 1;
    /** 液态玻璃：更强模糊 + 更高通透度 + 高光描边 */
    public static final int STYLE_LIQUID = 2;

    /** 降采样倍数：6 表示位图边长为源视图的 1/6 */
    private static final int DOWNSCALE = 6;
    /**
     * 采样区域外扩（px）。
     *
     * <p>两个作用：
     * <ol>
     *   <li><b>边缘均匀</b>：盒式模糊在图像边界会做夹取（clamp），若采样区正好等于玻璃层，
     *       四条边会出现明显的「变硬」带，观感就是模糊不均匀。外扩后只取中间区域绘制，
     *       边缘同样参与模糊；</li>
     *   <li><b>超限位移余量</b>：外扩出来的部分可作为绘制阶段的位移缓冲，让模糊随下拉 / 回弹
     *       做有界平移，而不需要逐帧重采样。</li>
     * </ol>
     */
    private static final int BLUR_MARGIN_PX = 28;
    /** 位图保护上限（像素），异常大的采样区域不做模糊以免 OOM */
    private static final int MAX_SOURCE_PIXELS = 8_000_000;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 蒙版：上浅下深的纵向渐变，让玻璃有「受光面」，而不是一块平涂色 */
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 顶部柔光（仅液态玻璃），模拟玻璃受光后的高光带 */
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Nullable
    private Shader tintShader;
    @Nullable
    private Shader rimShader;
    private int shaderKey;
    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();
    private final Rect dstRect = new Rect();
    private final int[] selfLocation = new int[2];
    private final int[] sourceLocation = new int[2];
    private final Runnable refreshRunnable = this::refresh;

    @Nullable
    private View source;
    @Nullable
    private Bitmap backdrop;
    @Nullable
    private Canvas backdropCanvas;

    private int glassStyle = STYLE_NONE;
    private int cornerRadiusPx;
    private int tintColor = Color.TRANSPARENT;
    private int rimColor = Color.TRANSPARENT;
    private boolean refreshScheduled;
    /** 本次采样在源视图中的区域（源本地坐标，已含外扩） */
    private final Rect regionRect = new Rect();
    /** 模糊像素缓冲，复用避免每帧产生大对象、触发 GC 抖动 */
    private int[] pixelsBuf;
    private int[] tempBuf;
    /** 绘制阶段的纵向视觉位移（px）：用于跟随下拉 / 回弹，不触发重采样 */
    private float drawOffsetY;
    /** 过渡跟随的截止时间戳（毫秒），期间按帧重采样 */
    private long followUntil;
    private final Runnable followRunnable = new Runnable() {
        @Override
        public void run() {
            if (SystemClock.uptimeMillis() > followUntil) {
                return;
            }
            markDirty();
            postOnAnimation(this);
        }
    };

    public BackdropBlurView(Context context) {
        this(context, null);
    }

    public BackdropBlurView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        // 玻璃层纯装饰：不拦截触摸、不参与无障碍焦点
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /**
     * 设置被模糊的内容源（底栏背后真正滚动的那块内容）。
     *
     * <p>切换来源时<b>不立即回收</b>旧位图：回收后到下一帧重采样之间会只剩一层蒙版色，
     * 观感上就是「切换页面时模糊突然衰减一下又恢复」。保留旧位图并在下一帧覆盖，
     * 最多只有一帧轻微失真的模糊，不会有闪断。
     */
    public void setSourceView(@Nullable View view) {
        if (source == view) {
            return;
        }
        source = view;
        cancelScheduledRefresh();
        drawOffsetY = 0f;
        if (view == null || glassStyle == STYLE_NONE) {
            recycle();
            invalidate();
            return;
        }
        markDirty();
    }

    /** 设置玻璃样式与配色；{@code STYLE_NONE} 时停止一切绘制与位图分配 */
    public void setGlassStyle(int style, int tint, int rim) {
        boolean changed = glassStyle != style;
        glassStyle = style;
        tintColor = tint;
        rimColor = rim;
        shaderKey = 0;
        if (style == STYLE_NONE) {
            cancelScheduledRefresh();
            recycle();
        } else if (changed) {
            // 样式变化会改变模糊轮数，下一帧重新采样即可。
            markDirty();
        }
        invalidate();
    }

    public int getGlassStyle() {
        return glassStyle;
    }

    /** 圆角半径（px），需与底栏外壳保持一致 */
    public void setCornerRadiusPx(int radius) {
        if (cornerRadiusPx != radius) {
            cornerRadiusPx = radius;
            invalidate();
        }
    }

    /**
     * 设置绘制阶段的纵向位移（px）。
     *
     * <p>下拉 / 回弹时内容在屏幕上有位移但 {@code scrollY} 不变。这里<b>只平移已采样的模糊位图</b>，
     * 不触发重采样：既能让模糊与超限动画同步，又不会额外占用主线程帧预算。
     * 可平移的范围由采样外扩量（{@link #BLUR_MARGIN_PX}）保证，不会露出空白。
     */
    public void setDrawOffsetY(float offset) {
        if (Math.abs(drawOffsetY - offset) < 0.5f) {
            return;
        }
        drawOffsetY = offset;
        invalidate();
    }

    /** 当前绘制位移（px），供外部做回弹动画时取起点 */
    public float getDrawOffsetY() {
        return drawOffsetY;
    }

    /**
     * 在一段时间内按帧跟随重采样（页面切换等连续变化场景）。
     *
     * <p>交叉淡入淡出期间，底栏背后的合成结果每帧都在变；若只在切换开始采样一次，
     * 新页面完全显现前模糊会一直停在旧内容上，表现为「模糊滞后出现」。
     */
    public void followFor(long durationMs) {
        if (glassStyle == STYLE_NONE) {
            return;
        }
        followUntil = SystemClock.uptimeMillis() + Math.max(0L, durationMs);
        removeCallbacks(followRunnable);
        postOnAnimation(followRunnable);
    }

    // ------------------------------------------------------------------
    // 刷新调度
    // ------------------------------------------------------------------

    /** 立即重建模糊位图（布局变化、页面切换等低频场景使用） */
    public void refresh() {
        cancelScheduledRefresh();
        if (glassStyle == STYLE_NONE) {
            recycle();
            postInvalidateOnAnimation();
            return;
        }
        capture();
        postInvalidateOnAnimation();
    }

    /**
     * 按帧刷新：相当于 Web 里的 requestAnimationFrame。
     *
     * <p>滚动、输入法联动、页面切换时只把重建任务挂到下一帧，保证同一帧内无论收到多少次
     * 位置变化通知，都只重采样一次；同时限制最小间隔（约 30fps），避免软件重绘占满主线程，
     * 保证滚动超限 / 回弹动画始终有帧预算可用。
     */
    public void markDirty() {
        if (glassStyle == STYLE_NONE || refreshScheduled) {
            return;
        }
        // 每帧最多重采样一次：统一挂到下一帧的动画回调上，避免「延迟 16ms + 帧对齐」
        // 造成的 16/33ms 交替，观感上就是模糊刷新时快时慢。
        refreshScheduled = true;
        postOnAnimation(refreshRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelScheduledRefresh();
        removeCallbacks(followRunnable);
        followUntil = 0L;
        recycle();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            // 同理：尺寸变化（页面选择器收起、底栏样式切换）时保留旧位图，
            // 由下一帧的 capture() 自行重建，避免露出一帧纯蒙版色。
            markDirty();
        }
    }

    private void cancelScheduledRefresh() {
        refreshScheduled = false;
        removeCallbacks(refreshRunnable);
    }

    private void recycle() {
        if (backdrop != null) {
            Bitmap old = backdrop;
            backdrop = null;
            if (backdropCanvas != null) {
                backdropCanvas.setBitmap(null);
            }
            try {
                if (!old.isRecycled()) {
                    old.recycle();
                }
            } catch (Throwable ignored) {
                // 回收失败无需处理
            }
        }
    }

    // ------------------------------------------------------------------
    // 采样与模糊
    // ------------------------------------------------------------------

    private void capture() {
        View src = source;
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (src == null || viewWidth <= 0 || viewHeight <= 0
                || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return;
        }
        getLocationInWindow(selfLocation);
        src.getLocationInWindow(sourceLocation);
        int left = selfLocation[0] - sourceLocation[0];
        int top = selfLocation[1] - sourceLocation[1];
        // 采样区域 = 玻璃层矩形 + 外扩。外扩部分既参与模糊（消除边缘夹取造成的「硬边」），
        // 也作为绘制阶段超限位移的缓冲。
        int l = clamp(left - BLUR_MARGIN_PX, 0, src.getWidth());
        int t = clamp(top - BLUR_MARGIN_PX, 0, src.getHeight());
        int r = clamp(left + viewWidth + BLUR_MARGIN_PX, 0, src.getWidth());
        int b = clamp(top + viewHeight + BLUR_MARGIN_PX, 0, src.getHeight());
        int regionWidth = r - l;
        int regionHeight = b - t;
        if (regionWidth <= 0 || regionHeight <= 0) {
            return;
        }
        if ((long) regionWidth * regionHeight > MAX_SOURCE_PIXELS) {
            recycle();
            return;
        }
        regionRect.set(l, t, r, b);

        int w = Math.max(1, regionWidth / DOWNSCALE);
        int h = Math.max(1, regionHeight / DOWNSCALE);
        if (backdrop == null || backdrop.getWidth() != w || backdrop.getHeight() != h) {
            recycle();
            try {
                backdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError e) {
                backdrop = null;
                return;
            }
            backdropCanvas = new Canvas(backdrop);
        }
        if (backdropCanvas == null) {
            return;
        }
        backdrop.eraseColor(Color.TRANSPARENT);
        int save = backdropCanvas.save();
        // 只采样玻璃层及其外扩区域，而不是整页内容：位图尺寸只和底栏区域相关
        backdropCanvas.scale((float) w / regionWidth, (float) h / regionHeight);
        backdropCanvas.translate(-l, -t);
        try {
            src.draw(backdropCanvas);
        } catch (Throwable t1) {
            recycle();
            return;
        } finally {
            try {
                backdropCanvas.restoreToCount(save);
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        boolean liquid = glassStyle == STYLE_LIQUID;
        boxBlur(backdrop, liquid ? 3 : 2);
    }

    /**
     * 盒式模糊（就地修改）。横竖各扫一遍为一轮，两轮即可近似高斯，
     * 由于位图已被降采样，单轮成本为 O(像素数)，整体非常轻量。
     *
     * <p>像素缓冲复用为成员变量：60fps 下每帧分配两个 int[] 会造成持续 GC，
     * 表现为掉帧与「模糊刷新不匀」。
     */
    private void boxBlur(Bitmap bitmap, int rounds) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int size = w * h;
        if (pixelsBuf == null || pixelsBuf.length != size) {
            pixelsBuf = new int[size];
            tempBuf = new int[size];
        }
        bitmap.getPixels(pixelsBuf, 0, w, 0, 0, w, h);
        for (int i = 0; i < rounds; i++) {
            blurHorizontal(pixelsBuf, tempBuf, w, h);
            blurVertical(tempBuf, pixelsBuf, w, h);
        }
        bitmap.setPixels(pixelsBuf, 0, w, 0, 0, w, h);
    }

    private static void blurHorizontal(int[] in, int[] out, int w, int h) {
        int radius = 2;
        int window = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = in[row + clamp(i, 0, w - 1)];
                a += (c >>> 24) & 0xFF;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / window) << 24) | ((r / window) << 16)
                        | ((g / window) << 8) | (b / window);
                int leave = in[row + clamp(x - radius, 0, w - 1)];
                int enter = in[row + clamp(x + radius + 1, 0, w - 1)];
                a += ((enter >>> 24) & 0xFF) - ((leave >>> 24) & 0xFF);
                r += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                g += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                b += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int w, int h) {
        int radius = 2;
        int window = radius * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0;
            int r = 0;
            int g = 0;
            int b = 0;
            for (int i = -radius; i <= radius; i++) {
                int c = in[clamp(i, 0, h - 1) * w + x];
                a += (c >>> 24) & 0xFF;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / window) << 24) | ((r / window) << 16)
                        | ((g / window) << 8) | (b / window);
                int leave = in[clamp(y - radius, 0, h - 1) * w + x];
                int enter = in[clamp(y + radius + 1, 0, h - 1) * w + x];
                a += ((enter >>> 24) & 0xFF) - ((leave >>> 24) & 0xFF);
                r += ((enter >> 16) & 0xFF) - ((leave >> 16) & 0xFF);
                g += ((enter >> 8) & 0xFF) - ((leave >> 8) & 0xFF);
                b += (enter & 0xFF) - (leave & 0xFF);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (glassStyle == STYLE_NONE || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (backdrop == null || backdrop.isRecycled() || backdropCanvas == null) {
            // 尚未完成首次采样：至少保证有表面色垫底，避免出现空洞
            drawTint(canvas);
            return;
        }
        View src = source;
        if (src == null) {
            drawTint(canvas);
            return;
        }
        getLocationInWindow(selfLocation);
        src.getLocationInWindow(sourceLocation);
        int viewLeft = selfLocation[0] - sourceLocation[0];
        int viewTop = selfLocation[1] - sourceLocation[1];
        // 采样区自带外扩，因此位图绘制范围可以略大于自身；
        // 位移量受外扩余量约束，保证任何偏移下都不会露出空白。
        int maxDown = Math.max(0, viewTop - regionRect.top);
        int maxUp = Math.max(0, regionRect.bottom - (viewTop + getHeight()));
        float offset = Math.max(-maxUp, Math.min(maxDown, drawOffsetY));
        dstRect.set(regionRect.left - viewLeft,
                regionRect.top - viewTop + Math.round(offset),
                regionRect.right - viewLeft,
                regionRect.bottom - viewTop + Math.round(offset));

        int save = applyClip(canvas);
        canvas.drawBitmap(backdrop, null, dstRect, bitmapPaint);
        ensureShaders(getHeight());
        if (tintShader != null) {
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), tintPaint);
        } else {
            canvas.drawColor(tintColor, PorterDuff.Mode.SRC_OVER);
        }
        if (glassStyle == STYLE_LIQUID) {
            // 顶部柔光带：让玻璃有明确的受光方向（Expressive 材质的关键观感）
            canvas.drawRect(0f, 0f, getWidth(), Math.max(1f, getHeight() * 0.55f), sheenPaint);
            // 液态玻璃：自上而下渐隐的细描边，模拟玻璃棱边
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(getResources().getDisplayMetrics().density);
            strokePaint.setShader(rimShader);
            if (rimShader == null) {
                strokePaint.setColor(rimColor);
            }
            canvas.drawRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, strokePaint);
            strokePaint.setShader(null);
        }
        canvas.restoreToCount(save);
    }

    private void drawTint(Canvas canvas) {
        int save = applyClip(canvas);
        ensureShaders(getHeight());
        if (tintShader != null) {
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), tintPaint);
        } else {
            canvas.drawColor(tintColor, PorterDuff.Mode.SRC_OVER);
        }
        canvas.restoreToCount(save);
    }

    /**
     * 构建材质所需的纵向渐变（随高度 / 配色变化时重建一次，不在每帧创建对象）。
     *
     * <p>蒙版顶部略通透、底部更实：既保留「透出内容」的玻璃感，又保证底栏图标
     * 始终有足够对比度；液态玻璃额外叠一层顶部柔光与渐隐棱边。
     */
    private void ensureShaders(int height) {
        int key = (height * 31 + tintColor) * 31 + rimColor + glassStyle * 7;
        if (shaderKey == key && tintShader != null) {
            return;
        }
        if (height <= 0) {
            return;
        }
        shaderKey = key;
        tintShader = new LinearGradient(0f, 0f, 0f, height,
                fadeAlpha(tintColor, 0.86f), tintColor, Shader.TileMode.CLAMP);
        tintPaint.setShader(tintShader);
        if (glassStyle == STYLE_LIQUID) {
            rimShader = new LinearGradient(0f, 0f, 0f, height,
                    rimColor, fadeAlpha(rimColor, 0.16f), Shader.TileMode.CLAMP);
            sheenPaint.setShader(new LinearGradient(0f, 0f, 0f,
                    Math.max(1f, height * 0.55f),
                    fadeAlpha(Color.WHITE, 0.07f), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        } else {
            rimShader = null;
            sheenPaint.setShader(null);
        }
    }

    /** 按系数缩放颜色自身的 alpha（用于同一配色的明暗/浓淡派生） */
    private static int fadeAlpha(int color, float factor) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, factor)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int applyClip(Canvas canvas) {
        int save = canvas.save();
        if (cornerRadiusPx > 0) {
            clipRect.set(0, 0, getWidth(), getHeight());
            clipPath.reset();
            clipPath.addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
            canvas.clipPath(clipPath);
        }
        return save;
    }
}
