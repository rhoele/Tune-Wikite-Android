package com.auramusic.wiki.classic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * 系统振动反馈统一入口（第二阶段：控件全维度体验升级）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>走 {@link View#performHapticFeedback(int)}，即「系统级」触感反馈——
 *       无需 {@code VIBRATE} 权限，且自动尊重用户的系统触感开关与强度设置；</li>
 *   <li>不同交互场景映射到不同的 {@link HapticFeedbackConstants}，形成差异化的触感语言：
 *       轻点走 tick、长按走 long-press、切换走 context-click、滑动走 handle-move，
 *       确认 / 拒绝在 Android 11+ 使用系统语义化常量；</li>
 *   <li>三档强度（轻 / 标准 / 强）在同一场景内选用更轻或更重的常量，
 *       在旧系统上自动回落到该版本可用的等价常量，保证不出现「无反馈」。</li>
 * </ul>
 */
public final class HapticsManager {

    /** 单击（按钮、卡片、列表项） */
    public static final int SCENE_TAP = 0;
    /** 长按 */
    public static final int SCENE_LONG_PRESS = 1;
    /** 切换 / 勾选（Tab、Chip、开关、单选） */
    public static final int SCENE_TOGGLE = 2;
    /** 滑动 / 翻页 */
    public static final int SCENE_SWIPE = 3;
    /** 操作成功（登录成功、提交完成） */
    public static final int SCENE_CONFIRM = 4;
    /** 操作被拒绝（校验失败、边界越界） */
    public static final int SCENE_REJECT = 5;

    private HapticsManager() {
    }

    /** 总开关 + 当前强度，供调用方决定是否还要播放配套动效。 */
    public static boolean enabled(Context context) {
        return AppearanceManager.hapticsEnabled(context);
    }

    public static void tap(View view) {
        perform(view, SCENE_TAP);
    }

    public static void longPress(View view) {
        perform(view, SCENE_LONG_PRESS);
    }

    public static void toggle(View view) {
        perform(view, SCENE_TOGGLE);
    }

    public static void swipe(View view) {
        perform(view, SCENE_SWIPE);
    }

    public static void confirm(View view) {
        perform(view, SCENE_CONFIRM);
    }

    public static void reject(View view) {
        perform(view, SCENE_REJECT);
    }

    /**
     * 按场景与用户强度档位触发一次触感反馈。
     * 任何异常（部分 ROM 的模糊实现、无触感马达的设备）都静默忽略，绝不影响主流程。
     */
    public static void perform(View view, int scene) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        if (context == null || !enabled(context)) {
            return;
        }
        int constant = constantFor(scene, AppearanceManager.hapticLevel(context),
                Build.VERSION.SDK_INT);
        try {
            view.performHapticFeedback(constant);
        } catch (Throwable ignored) {
            // 触感反馈是纯增强项，失败不影响业务
        }
    }

    /**
     * 场景 + 强度 → 系统触感常量。
     *
     * <p>抽成纯函数（显式传入 sdkInt）以便在 JVM 单测中锁定契约。
     *
     * <p>注意：这里不能拿「0」当「无可用常量」的哨兵值——{@link HapticFeedbackConstants#LONG_PRESS}
     * 本身就是 0，用它当哨兵会把长按场景的反馈整条吞掉。所有分支都返回一个真实可用的常量。
     *
     * <p>CONFIRM / GESTURE_END / REJECT / TEXT_HANDLE_MOVE 是「新版本才引入的常量」，
     * 由 {@code semantic} 分支结合 sdkInt 参数保证只在受支持的版本上被选中；
     * 且它们是编译期内联的 int 字面量，在旧设备上也不会触发类校验失败，故显式抑制 lint。
     */
    @SuppressLint("InlinedApi")
    static int constantFor(int scene, int level, int sdkInt) {
        boolean semantic = sdkInt >= Build.VERSION_CODES.R; // Android 11：语义化触感常量
        switch (scene) {
            case SCENE_LONG_PRESS:
                return level == AppearanceManager.HAPTIC_LIGHT
                        ? HapticFeedbackConstants.CLOCK_TICK
                        : HapticFeedbackConstants.LONG_PRESS;
            case SCENE_TOGGLE:
                if (level == AppearanceManager.HAPTIC_LIGHT) {
                    return HapticFeedbackConstants.CLOCK_TICK;
                }
                if (level == AppearanceManager.HAPTIC_STRONG && semantic) {
                    return HapticFeedbackConstants.CONFIRM;
                }
                return HapticFeedbackConstants.CONTEXT_CLICK;
            case SCENE_SWIPE:
                if (level == AppearanceManager.HAPTIC_LIGHT) {
                    return HapticFeedbackConstants.CLOCK_TICK;
                }
                if (level == AppearanceManager.HAPTIC_STRONG && semantic) {
                    return HapticFeedbackConstants.GESTURE_END;
                }
                return HapticFeedbackConstants.TEXT_HANDLE_MOVE;
            case SCENE_CONFIRM:
                return semantic ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
            case SCENE_REJECT:
                return semantic ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
            case SCENE_TAP:
            default:
                if (level == AppearanceManager.HAPTIC_LIGHT) {
                    return HapticFeedbackConstants.CLOCK_TICK;
                }
                if (level == AppearanceManager.HAPTIC_STRONG) {
                    return HapticFeedbackConstants.LONG_PRESS;
                }
                return HapticFeedbackConstants.VIRTUAL_KEY;
        }
    }
}
