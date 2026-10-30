package com.auramusic.wiki.classic;

/**
 * 下载「连续无数据」看门狗。
 *
 * <p>下载卡死的主因不是总耗时太长，而是响应体停止到达后既没有数据、也没有终态回调：
 * 只限制「整段下载总时长」会让线程在无数据状态下空等到 30 分钟。这里只关心
 * <b>距离上一次收到数据过了多久</b>，每收到一块数据就 {@link #touch()} 续期。
 *
 * <p>纯逻辑、时钟可注入，便于单元测试；Android 侧用 {@link #ofSystemClock(long)}。
 */
final class DownloadIdleGuard {

    /** 时钟抽象：生产用单调时钟，测试用可推进的假时钟。 */
    interface Clock {
        long nowMs();
    }

    private final long idleTimeoutMs;
    private final Clock clock;
    private long lastActivityMs;
    private boolean expired;

    DownloadIdleGuard(long idleTimeoutMs, Clock clock) {
        this.idleTimeoutMs = Math.max(1L, idleTimeoutMs);
        this.clock = clock;
        this.lastActivityMs = clock.nowMs();
    }

    /** 生产用：单调时钟不受系统时间修改影响。 */
    static DownloadIdleGuard ofSystemClock(long idleTimeoutMs) {
        return new DownloadIdleGuard(idleTimeoutMs, android.os.SystemClock::elapsedRealtime);
    }

    /** 收到任意数据（含响应头）时调用，重置无数据计时。 */
    synchronized void touch() {
        lastActivityMs = clock.nowMs();
    }

    /** 距离上一次活动的毫秒数。 */
    synchronized long idleMs() {
        return Math.max(0L, clock.nowMs() - lastActivityMs);
    }

    /**
     * 判定是否已经超时。
     *
     * @return true 表示连续无数据已达到阈值；一旦返回 true 会保持为 true（只触发一次动作）
     */
    synchronized boolean checkExpired() {
        if (!expired && idleMs() >= idleTimeoutMs) {
            expired = true;
        }
        return expired;
    }

    synchronized boolean expired() {
        return expired;
    }

    long idleTimeoutMs() {
        return idleTimeoutMs;
    }
}
