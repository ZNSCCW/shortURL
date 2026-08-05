package com.shorturl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginRateLimiter 单元测试：防暴力破解逻辑。
 * 规则：同一 IP 60 秒窗口内最多 5 次失败，第 5 次触发封锁（5 分钟）。
 */
class LoginRateLimiterTest {

    private final LoginRateLimiter limiter = new LoginRateLimiter();

    @Test
    void shouldAllowUpToFourFailuresWithoutBlocking() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("1.2.3.4");
            assertThat(limiter.isBlocked("1.2.3.4"))
                    .as("第 %d 次失败后不应封锁", i + 1)
                    .isFalse();
        }
    }

    @Test
    void shouldBlockAfterFifthFailure() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
    }

    @Test
    void clearOnSuccess_shouldUnblock() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.2.3.4");
        }
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();

        limiter.clearOnSuccess("1.2.3.4");

        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void blockShouldBeIsolatedPerIp() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("10.0.0.1");
        }
        assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
        assertThat(limiter.isBlocked("10.0.0.2")).isFalse();
    }
}
