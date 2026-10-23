package com.auramusic.wiki.classic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.view.HapticFeedbackConstants;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 触感反馈映射契约的单测（第二阶段：控件全维度体验升级）。
 *
 * <p>{@link HapticsManager#constantFor(int, int, int)} 是纯函数，显式接收 sdkInt，
 * 因此可以在 JVM 上精确锁定「场景 × 强度 × 系统版本」的完整映射表，
 * 防止后续调整时破坏 Android 10 及以下与 Android 11+ 的差异化语义化触感。
 */
public class HapticsManagerTest {

    /** Android 10（无语义化触感常量） */
    private static final int SDK_LEGACY = Build.VERSION_CODES.Q;
    /** Android 11+（具备 CONFIRM / REJECT / GESTURE_END 等语义化触感常量） */
    private static final int SDK_SEMANTIC = Build.VERSION_CODES.R;

    private static final int LIGHT = AppearanceManager.HAPTIC_LIGHT;
    private static final int STANDARD = AppearanceManager.HAPTIC_STANDARD;
    private static final int STRONG = AppearanceManager.HAPTIC_STRONG;

    @Test
    public void tapMapsByLevel() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK,
                HapticsManager.constantFor(HapticsManager.SCENE_TAP, LIGHT, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticsManager.constantFor(HapticsManager.SCENE_TAP, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.LONG_PRESS,
                HapticsManager.constantFor(HapticsManager.SCENE_TAP, STRONG, SDK_SEMANTIC));
    }

    @Test
    public void tapIgnoresSystemVersion() {
        // 轻点不依赖语义化常量，两个系统版本结果一致
        for (int level : new int[]{LIGHT, STANDARD, STRONG}) {
            assertEquals(
                    HapticsManager.constantFor(HapticsManager.SCENE_TAP, level, SDK_SEMANTIC),
                    HapticsManager.constantFor(HapticsManager.SCENE_TAP, level, SDK_LEGACY));
        }
    }

    @Test
    public void longPressOnlyLightensAtLightLevel() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK,
                HapticsManager.constantFor(HapticsManager.SCENE_LONG_PRESS, LIGHT, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.LONG_PRESS,
                HapticsManager.constantFor(HapticsManager.SCENE_LONG_PRESS, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.LONG_PRESS,
                HapticsManager.constantFor(HapticsManager.SCENE_LONG_PRESS, STRONG, SDK_SEMANTIC));
    }

    @Test
    public void toggleUsesSemanticConfirmOnlyOnModernSystems() {
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK,
                HapticsManager.constantFor(HapticsManager.SCENE_TOGGLE, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.CONFIRM,
                HapticsManager.constantFor(HapticsManager.SCENE_TOGGLE, STRONG, SDK_SEMANTIC));
        // 旧系统上「强」无法用语义化常量，回落为 CONTEXT_CLICK 而不是直接失效
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK,
                HapticsManager.constantFor(HapticsManager.SCENE_TOGGLE, STRONG, SDK_LEGACY));
        assertEquals(HapticFeedbackConstants.CLOCK_TICK,
                HapticsManager.constantFor(HapticsManager.SCENE_TOGGLE, LIGHT, SDK_LEGACY));
    }

    @Test
    public void swipeUsesSemanticGestureEndOnlyOnModernSystems() {
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                HapticsManager.constantFor(HapticsManager.SCENE_SWIPE, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.GESTURE_END,
                HapticsManager.constantFor(HapticsManager.SCENE_SWIPE, STRONG, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                HapticsManager.constantFor(HapticsManager.SCENE_SWIPE, STRONG, SDK_LEGACY));
        assertEquals(HapticFeedbackConstants.CLOCK_TICK,
                HapticsManager.constantFor(HapticsManager.SCENE_SWIPE, LIGHT, SDK_SEMANTIC));
    }

    @Test
    public void confirmAndRejectFallBackOnLegacySystems() {
        assertEquals(HapticFeedbackConstants.CONFIRM,
                HapticsManager.constantFor(HapticsManager.SCENE_CONFIRM, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticsManager.constantFor(HapticsManager.SCENE_CONFIRM, STANDARD, SDK_LEGACY));

        assertEquals(HapticFeedbackConstants.REJECT,
                HapticsManager.constantFor(HapticsManager.SCENE_REJECT, STANDARD, SDK_SEMANTIC));
        assertEquals(HapticFeedbackConstants.LONG_PRESS,
                HapticsManager.constantFor(HapticsManager.SCENE_REJECT, STANDARD, SDK_LEGACY));
    }

    @Test
    public void everySceneProducesFeedbackConstant() {
        int[] scenes = {
                HapticsManager.SCENE_TAP,
                HapticsManager.SCENE_LONG_PRESS,
                HapticsManager.SCENE_TOGGLE,
                HapticsManager.SCENE_SWIPE,
                HapticsManager.SCENE_CONFIRM,
                HapticsManager.SCENE_REJECT,
        };
        // LONG_PRESS 的取值就是 0，因此不能拿 0 当「无效」判据，只能校验结果落在已知常量集合内
        Set<Integer> valid = new HashSet<>(Arrays.asList(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.CONTEXT_CLICK,
                HapticFeedbackConstants.TEXT_HANDLE_MOVE,
                HapticFeedbackConstants.GESTURE_END,
                HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.REJECT));
        for (int scene : scenes) {
            for (int level : new int[]{LIGHT, STANDARD, STRONG}) {
                for (int sdk : new int[]{SDK_LEGACY, SDK_SEMANTIC}) {
                    int constant = HapticsManager.constantFor(scene, level, sdk);
                    assertTrue("scene=" + scene + " level=" + level + " sdk=" + sdk
                            + " -> " + constant, valid.contains(constant));
                }
            }
        }
    }
}
