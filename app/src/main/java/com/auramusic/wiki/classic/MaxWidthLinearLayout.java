package com.auramusic.wiki.classic;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * 内容最大宽度容器。
 *
 * <p>大屏（平板 / 折叠屏展开态）上如果让表单和列表铺满整屏，行长会超过可读范围，
 * 卡片也会被拉成一条极宽的横条。这里把测量宽度收敛到 {@code content_max_width}
 * （手机 640dp / sw600dp 840dp），配合父容器的 {@code layout_gravity=center_horizontal}
 * 实现「内容居中、两侧留白」。
 *
 * <p>手机竖屏宽度小于该阈值，测量规格不会改变，因此对现有手机布局零影响。
 */
public class MaxWidthLinearLayout extends LinearLayout {

    private final int maxWidth;

    public MaxWidthLinearLayout(Context context) {
        this(context, null);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxWidthLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        maxWidth = getResources().getDimensionPixelSize(R.dimen.content_max_width);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int size = MeasureSpec.getSize(widthMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && maxWidth > 0 && size > maxWidth) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
