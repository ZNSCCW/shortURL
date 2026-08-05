package com.shorturl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShortenRateLimiter 单元测试：短链接创建速率限制。
 * 规则：同一 IP 每分钟最多 60 次创建请求，超限返回拒绝。
 */
class ShortenRateLimiterTest {

    private final ShortenRateLimiter limiter = new ShortenRateLimiter();

    @Test
    void shouldAllowUpToSixtyRequests() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryConsume("1.2.3.4"))
                    .as("第 %d 次请求应被允许", i + 1)
                    .isTrue();
        }
    }

    @Test
    void shouldRejectRequestsBeyondLimit() {
        for (int i = 0; i < 60; i++) {
            limiter.tryConsume("1.2.3.4");
        }
        assertThat(limiter.tryConsume("1.2.3.4")).isFalse();
        assertThat(limiter.tryConsume("1.2.3.4")).isFalse();
    }

    @Test
    void limitShouldBeIsolatedPerIp() {
        for (int i = 0; i < 60; i++) {
            limiter.tryConsume("10.0.0.1");
        }
        assertThat(limiter.tryConsume("10.0.0.1")).isFalse();
        assertThat(limiter.tryConsume("10.0.0.2")).isTrue();
    }
}
