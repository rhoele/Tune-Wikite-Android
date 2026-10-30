package com.auramusic.wiki.classic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 下载「连续无数据」看门狗契约。
 *
 * <p>覆盖线上真实故障：响应体停住后既不报错也不结束，用户停在一个固定百分比上。
 * 这里只验证纯逻辑，时钟用可控假时钟推进。
 */
public class DownloadIdleGuardTest {

    /** 可推进的假时钟 */
    private static final class FakeClock implements DownloadIdleGuard.Clock {
        long now = 1_000L;

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    @Test
    public void expiresOnlyAfterIdleTimeout() {
        FakeClock clock = new FakeClock();
        DownloadIdleGuard guard = new DownloadIdleGuard(60_000L, clock);

        clock.advance(59_000L);
        assertFalse("未到阈值不能判超时", guard.checkExpired());

        clock.advance(1_000L);
        assertTrue("连续无数据达到阈值应判超时", guard.checkExpired());
        assertTrue(guard.expired());
    }

    @Test
    public void activityPostponesTimeout() {
        FakeClock clock = new FakeClock();
        DownloadIdleGuard guard = new DownloadIdleGuard(60_000L, clock);

        // 每 30s 续期一次：总时长早已超过 60s，但不该超时
        for (int i = 0; i < 10; i++) {
            clock.advance(30_000L);
            guard.touch();
            assertFalse("持续有数据时不得判超时", guard.checkExpired());
        }
    }

    @Test
    public void timeoutStaysLatched() {
        FakeClock clock = new FakeClock();
        DownloadIdleGuard guard = new DownloadIdleGuard(1_000L, clock);
        clock.advance(1_500L);
        assertTrue(guard.checkExpired());
        // 超时后再续期也不应"复活"：动作只触发一次
        guard.touch();
        assertTrue(guard.checkExpired());
    }

    @Test
    public void idleMsReportsElapsedSinceLastActivity() {
        FakeClock clock = new FakeClock();
        DownloadIdleGuard guard = new DownloadIdleGuard(60_000L, clock);
        clock.advance(5_000L);
        assertTrue(guard.idleMs() >= 5_000L);
        guard.touch();
        assertTrue(guard.idleMs() < 1_000L);
    }
}
