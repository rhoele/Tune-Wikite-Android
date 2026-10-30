package com.auramusic.wiki.classic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 滚动显隐状态机契约：方向累计、各控件独立阈值、顶部复位、状态标签依赖吸顶位置。
 */
public class ScrollChromeStateTest {

    /** 底栏：下滑 100 / 上滑 20；状态标签：下滑 60 / 上滑 20 */
    private static ScrollChromeState state() {
        return new ScrollChromeState(100, 20, 60, 20);
    }

    @Test
    public void hidesAfterAccumulatedDownwardScroll() {
        ScrollChromeState s = state();
        assertFalse(s.update(30, false).navHidden);
        assertFalse(s.update(70, false).navHidden);
        assertTrue("累计下滑超过阈值应隐藏底栏", s.update(110, false).navHidden);
    }

    @Test
    public void ignoresTinyJitterBeforeThreshold() {
        ScrollChromeState s = state();
        s.update(60, false);
        // 抖动：下 3 上 2 下 4，累计仍不足阈值
        s.update(63, false);
        s.update(61, false);
        ScrollChromeState.State st = s.update(65, false);
        assertFalse(st.navHidden);
        assertFalse(st.chipsHidden);
    }

    @Test
    public void restoresAfterShortUpwardScroll() {
        ScrollChromeState s = state();
        s.update(400, true);
        assertTrue(s.state().navHidden);
        // 只用 20px 的反向滚动即可恢复，无需滚回顶部
        ScrollChromeState.State st = s.update(380, true);
        assertFalse(st.navHidden);
        assertFalse(st.chipsHidden);
    }

    @Test
    public void upwardPullsBelowThresholdKeepControlsHidden() {
        // 惯性回弹、手指抖动产生的十几 px 反向位移不应把控件顶回来
        ScrollChromeState s = new ScrollChromeState(100, 100, 100, 100);
        s.update(400, true);
        assertTrue(s.state().navHidden);
        // 吸顶那一帧不计入状态标签的累计，需要再下滑一段才会收起
        assertFalse(s.state().chipsHidden);
        s.update(520, true);
        assertTrue(s.state().chipsHidden);
        s.update(510, true);
        s.update(490, true);
        assertTrue("反向累计不足阈值时保持隐藏", s.state().navHidden);
        assertTrue(s.state().chipsHidden);
        assertFalse(s.update(380, true).navHidden);
    }

    @Test
    public void chipsHideOnlyWhenPinned() {
        ScrollChromeState s = state();
        ScrollChromeState.State st = s.update(200, false);
        assertTrue(st.navHidden);
        assertFalse("未吸顶时不应隐藏状态标签", st.chipsHidden);

        s.reset();
        st = s.update(200, true);
        assertTrue(st.navHidden);
        assertFalse("刚吸顶的一帧不收起", st.chipsHidden);
        assertTrue("吸顶后继续下滑达到阈值才收起", s.update(270, true).chipsHidden);
    }

    @Test
    public void chipsUseTheirOwnThreshold() {
        // 底栏阈值调大，单独观察状态标签的阈值
        ScrollChromeState s = new ScrollChromeState(200, 20, 60, 20);
        s.update(60, true);
        ScrollChromeState.State st = s.update(120, true);
        assertFalse("底栏按自己的阈值保持显示", st.navHidden);
        assertTrue("状态标签按自己的阈值收起", st.chipsHidden);
    }

    @Test
    public void chipsRestoreWhenLeavingPinnedPosition() {
        ScrollChromeState s = state();
        s.update(200, true);
        s.update(270, true);
        assertTrue(s.state().chipsHidden);
        // 上滑距离不足恢复阈值，但已离开吸顶位置：筛选入口必须恢复
        ScrollChromeState.State st = s.update(268, false);
        assertTrue(st.navHidden);
        assertFalse(st.chipsHidden);
    }

    @Test
    public void chipsNeedExtraScrollAfterPinning() {
        // 一路下滑到刚吸顶（吸顶前已累计 400px）
        ScrollChromeState s = state();
        s.update(100, false);
        s.update(200, false);
        s.update(300, false);
        s.update(400, true);
        assertFalse("刚吸顶不能因为吸顶前的位移立即收起状态标签", s.state().chipsHidden);
        assertFalse(s.update(450, true).chipsHidden);
        assertTrue("吸顶后继续累计达到阈值才收起", s.update(465, true).chipsHidden);
    }

    @Test
    public void topResetsEverything() {
        ScrollChromeState s = state();
        s.update(500, true);
        s.update(560, true);
        assertTrue(s.state().chipsHidden);
        ScrollChromeState.State st = s.update(0, false);
        assertFalse(st.navHidden);
        assertFalse(st.chipsHidden);
    }

    @Test
    public void resetClearsAccumulation() {
        ScrollChromeState s = state();
        s.update(90, false);
        s.reset();
        // 复位后旧的 90px 不应被继承
        assertFalse(s.update(50, false).navHidden);
    }

    @Test
    public void skipToAlignsBaselineWithoutCountingJump() {
        ScrollChromeState s = state();
        s.update(500, true);
        assertTrue(s.state().navHidden);
        s.skipTo(500);
        // 基准对齐后，小幅下滑不应立刻再次隐藏
        assertFalse(s.update(520, true).navHidden);
    }
}
