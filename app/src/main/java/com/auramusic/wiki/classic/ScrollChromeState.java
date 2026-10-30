package com.auramusic.wiki.classic;

/**
 * 「我的贡献」页滚动显隐状态机。
 *
 * <p>纯逻辑、无 Android 依赖，便于单元测试。负责<b>底栏</b>与<b>审核状态标签</b>的显隐判定：
 * <ul>
 *   <li>按滚动方向累计位移，而不是单次触摸事件；手指轻微抖动不会来回切换；</li>
 *   <li>向下累计超过各自的 {@code downThreshold} 才隐藏；向上累计超过各自的
 *       {@code upThreshold} 才恢复。恢复阈值不能太小，否则一次轻微回拉就把控件顶回来；</li>
 *   <li>审核状态标签只在分类标签吸顶后才允许隐藏，吸顶前始终显示；</li>
 *   <li>回到顶部（{@code scrollY <= 0}）立即恢复。</li>
 * </ul>
 */
final class ScrollChromeState {

    /** 一次滚动采样的判定结果。 */
    static final class State {
        final boolean navHidden;
        final boolean chipsHidden;

        State(boolean navHidden, boolean chipsHidden) {
            this.navHidden = navHidden;
            this.chipsHidden = chipsHidden;
        }
    }

    private final int navDownThreshold;
    private final int navUpThreshold;
    private final int chipsDownThreshold;
    private final int chipsUpThreshold;

    private int lastScrollY;
    private int downAccum;
    private int upAccum;
    /** 审核状态标签自己的下滑累计：吸顶那一刻清零，之后重新累计 */
    private int chipsDownAccum;
    private boolean navHidden;
    private boolean chipsHidden;
    /** 上一帧分类标签是否已吸顶，用于在「刚吸顶」这一帧重置审核标签的累计位移 */
    private boolean pinnablePrev;

    ScrollChromeState(int navDownPx, int navUpPx, int chipsDownPx, int chipsUpPx) {
        this.navDownThreshold = Math.max(1, navDownPx);
        this.navUpThreshold = Math.max(1, navUpPx);
        this.chipsDownThreshold = Math.max(1, chipsDownPx);
        this.chipsUpThreshold = Math.max(1, chipsUpPx);
    }

    /** 当前状态（不改变累计位移）。 */
    State state() {
        return new State(navHidden, chipsHidden);
    }

    /** 页面切换 / 重新加载 / 回到顶部时复位。 */
    void reset() {
        lastScrollY = 0;
        downAccum = 0;
        upAccum = 0;
        chipsDownAccum = 0;
        navHidden = false;
        chipsHidden = false;
        pinnablePrev = false;
    }

    /**
     * 复位并把基准位置对齐到当前 {@code scrollY}（不把切换筛选前累计的位移带进新状态）。
     *
     * <p>用于「切换分类 / 状态 / 翻页」后重新排布：此时列表位置没变，
     * 控件应恢复为可见，后续滚动从当前位置重新累计。
     */
    void skipTo(int scrollY) {
        reset();
        lastScrollY = Math.max(0, scrollY);
    }

    /**
     * 输入一次滚动采样。
     *
     * @param scrollY       滚动容器当前纵坐标
     * @param chipsPinnable 分类标签是否已经吸顶（审核状态标签此时才允许隐藏）
     */
    State update(int scrollY, boolean chipsPinnable) {
        int y = Math.max(0, scrollY);
        int delta = y - lastScrollY;
        lastScrollY = y;

        if (y == 0) {
            downAccum = 0;
            upAccum = 0;
            chipsDownAccum = 0;
            navHidden = false;
            chipsHidden = false;
            pinnablePrev = false;
            return state();
        }

        // 刚滑到吸顶：审核状态标签从这一刻起重新累计（本帧位移也不计入）。
        // 否则一路滚到吸顶时累计值早已超过收起阈值，标签会在吸顶同一帧立刻消失，
        // 与「吸顶后继续下滑 72dp 才收起」的设计不符。
        boolean justPinned = chipsPinnable && !pinnablePrev;
        pinnablePrev = chipsPinnable;

        if (delta > 0) {
            downAccum += delta;
            upAccum = 0;
            if (downAccum >= navDownThreshold) {
                navHidden = true;
            }
            if (chipsPinnable) {
                chipsDownAccum = justPinned ? 0 : chipsDownAccum + delta;
                if (chipsDownAccum >= chipsDownThreshold) {
                    chipsHidden = true;
                }
            } else {
                chipsDownAccum = 0;
            }
        } else if (delta < 0) {
            upAccum -= delta;
            downAccum = 0;
            chipsDownAccum = 0;
            if (upAccum >= navUpThreshold) {
                navHidden = false;
            }
            if (upAccum >= chipsUpThreshold) {
                chipsHidden = false;
            }
        }

        // 分类标签离开吸顶位置时，审核状态标签必须恢复：否则筛选入口会凭空消失
        if (!chipsPinnable) {
            chipsHidden = false;
        }
        return state();
    }
}

