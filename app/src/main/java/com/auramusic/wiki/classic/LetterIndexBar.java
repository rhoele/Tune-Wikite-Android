package com.auramusic.wiki.classic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 字母索引条（仅在「按名称排序」时显示）。
 *
 * <p>手指在条上按下 / 滑动时按纵坐标命中对应字母，并立即回调列表滚动到该分组；
 * 当前字母用主色圆点强调，与列表的滚动位置无关（只表示手指位置）。
 */
public class LetterIndexBar extends View {

    interface OnLetterListener {
        void onLetter(String letter, int index);
    }

    private final List<String> letters = new ArrayList<>();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int activeIndex = -1;
    private int paddingY;
    private float textSize;
    private float bubbleRadius;
    @Nullable
    private OnLetterListener listener;

    private final Runnable clearActive = () -> {
        if (activeIndex != -1) {
            activeIndex = -1;
            invalidate();
        }
    };

    public LetterIndexBar(Context context) {
        this(context, null);
    }

    public LetterIndexBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paddingY = Math.round(8f * getResources().getDisplayMetrics().density);
        textSize = 11f * getResources().getDisplayMetrics().scaledDensity;
        bubbleRadius = 10f * getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        activeTextPaint.setTextAlign(Paint.Align.CENTER);
        activeTextPaint.setFakeBoldText(true);
        applyColors();
        setClickable(true);
    }

    private void applyColors() {
        textPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        activeBgPaint.setColor(MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary));
        activeTextPaint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnPrimary));
    }

    void setOnLetterListener(@Nullable OnLetterListener listener) {
        this.listener = listener;
    }

    /** 更新索引字母；不足两个分组时自动隐藏（不值得占一列）。 */
    void setLetters(List<String> data) {
        letters.clear();
        if (data != null) {
            letters.addAll(data);
        }
        setVisibility(letters.size() < 2 ? GONE : VISIBLE);
        activeIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (letters.isEmpty() || getHeight() <= 0) {
            return;
        }
        int count = letters.size();
        float slot = (getHeight() - 2f * paddingY) / count;
        float size = Math.min(slot * 0.78f, textSize);
        textPaint.setTextSize(size);
        activeTextPaint.setTextSize(size);
        float cx = getWidth() / 2f;
        float radius = Math.min(slot * 0.5f, bubbleRadius);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float offset = -(metrics.ascent + metrics.descent) / 2f;
        for (int i = 0; i < count; i++) {
            float cy = paddingY + slot * (i + 0.5f);
            if (i == activeIndex) {
                canvas.drawCircle(cx, cy, radius, activeBgPaint);
                canvas.drawText(letters.get(i), cx, cy + offset, activeTextPaint);
            } else {
                canvas.drawText(letters.get(i), cx, cy + offset, textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                selectAt(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                removeCallbacks(clearActive);
                postDelayed(clearActive, 700L);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void selectAt(float y) {
        if (letters.isEmpty()) {
            return;
        }
        int count = letters.size();
        float slot = (getHeight() - 2f * paddingY) / count;
        if (slot <= 0f) {
            return;
        }
        int index = (int) ((y - paddingY) / slot);
        index = Math.max(0, Math.min(count - 1, index));
        if (index == activeIndex) {
            return;
        }
        activeIndex = index;
        HapticsManager.tap(this);
        invalidate();
        if (listener != null) {
            listener.onLetter(letters.get(index), index);
        }
    }
}
