package com.auramusic.wiki.classic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 1:1 封面裁剪视图：单指拖动、双指缩放，始终保持图片铺满裁剪框。
 * 导出时把当前可视化区域按比例渲染到固定尺寸位图。
 */
public class SquareCropView extends View {

    private final Matrix matrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private Bitmap bitmap;
    private float baseScale = 1f;
    private float scale = 1f;
    private float minScale = 1f;
    private float maxScale = 6f;
    private float tx;
    private float ty;

    private ScaleGestureDetector scaleDetector;
    private float lastX;
    private float lastY;

    public SquareCropView(Context context) {
        super(context);
        init(context);
    }

    public SquareCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SquareCropView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyScale(scale * detector.getScaleFactor(), detector.getFocusX(),
                                detector.getFocusY());
                        return true;
                    }
                });
        setBackgroundColor(0xFF111111);
    }

    public void setBitmap(Bitmap bmp) {
        bitmap = bmp;
        resetTransform();
        invalidate();
    }

    public boolean hasBitmap() {
        return bitmap != null && !bitmap.isRecycled();
    }

    private void resetTransform() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        int size = Math.min(getWidth(), getHeight());
        baseScale = Math.max((float) size / bitmap.getWidth(), (float) size / bitmap.getHeight());
        scale = baseScale;
        minScale = baseScale;
        tx = (size - bitmap.getWidth() * scale) / 2f;
        ty = (size - bitmap.getHeight() * scale) / 2f;
        clamp();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetTransform();
    }

    private void applyScale(float target, float focusX, float focusY) {
        float next = Math.max(minScale, Math.min(maxScale, target));
        float factor = next / scale;
        tx = focusX - (focusX - tx) * factor;
        ty = focusY - (focusY - ty) * factor;
        scale = next;
        clamp();
        invalidate();
    }

    private void clamp() {
        float size = Math.min(getWidth(), getHeight());
        float sw = bitmap.getWidth() * scale;
        float sh = bitmap.getHeight() * scale;
        tx = Math.min(0f, Math.max(size - sw, tx));
        ty = Math.min(0f, Math.max(size - sh, ty));
    }

    /**
     * 无障碍：自定义 onTouchEvent 的控件必须实现 performClick，
     * 否则 TalkBack 无法把触摸手势识别为可点击操作。
     */
    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    tx += event.getX() - lastX;
                    ty += event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    clamp();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            return;
        }
        drawMatrix.set(matrix);
        drawMatrix.reset();
        drawMatrix.postScale(scale, scale);
        drawMatrix.postTranslate(tx, ty);
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.save();
        canvas.clipRect(bounds);
        canvas.drawBitmap(bitmap, drawMatrix, paint);
        canvas.restore();
    }

    /** 按当前构图导出 size×size 的位图。 */
    public Bitmap export(int size) {
        if (bitmap == null) {
            return null;
        }
        float factor = size / (float) Math.min(getWidth(), getHeight());
        Matrix out = new Matrix();
        out.postScale(scale * factor, scale * factor);
        out.postTranslate(tx * factor, ty * factor);
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(bitmap, out, paint);
        return result;
    }
}
