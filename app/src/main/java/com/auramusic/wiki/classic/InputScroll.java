package com.auramusic.wiki.classic;

import android.view.MotionEvent;
import android.widget.EditText;

import androidx.annotation.Nullable;

/**
 * 多行输入框的「框内滚动」支持。
 *
 * <p>这些输入框通常位于 {@code NestedScrollView} 内：内容超出 maxLines 后，手指拖动
 * 默认会被外层滚动容器接管，用户无法在框内查看前面的内容。这里在按下时判断是否
 * 处于可滚动状态，可滚动就先禁止外层拦截，抬起后再恢复。
 */
final class InputScroll {

    private InputScroll() {
    }

    static void enable(@Nullable EditText... fields) {
        if (fields == null) {
            return;
        }
        for (final EditText field : fields) {
            if (field == null) {
                continue;
            }
            field.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        boolean scrollable = field.getLineCount() > Math.max(1, field.getMaxLines());
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(scrollable);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        break;
                    default:
                        break;
                }
                return false;
            });
        }
    }
}
