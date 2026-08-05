package com.shorturl.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于内存的登录速率限制，防暴力破解。
 * 同一 IP 每窗口内最多尝试 MAX_ATTEMPTS 次，超限后锁定 LOCK_DURATION 秒。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 60;
    private static final long LOCK_DURATION_SECONDS = 300; // 锁定 5 分钟

    private final ConcurrentHashMap<String, AttemptRecord> records = new ConcurrentHashMap<>();

    public boolean isBlocked(String clientIp) {
        cleanExpired();
        AttemptRecord record = records.get(clientIp);
        if (record == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        // 如果在锁定期内，拒绝
        if (record.lockedUntil > now) {
            return true;
        }
        // 窗口过期，重置
        if (now - record.windowStart > TimeUnit.SECONDS.toMillis(WINDOW_SECONDS)) {
            records.remove(clientIp);
            return false;
        }
        return record.failures >= MAX_ATTEMPTS;
    }

    public void recordFailure(String clientIp) {
        cleanExpired();
        long now = System.currentTimeMillis();
        AttemptRecord record = records.computeIfAbsent(
                clientIp,
                k -> new AttemptRecord(now)
        );

        // 窗口过期则重置
        if (now - record.windowStart > TimeUnit.SECONDS.toMillis(WINDOW_SECONDS)) {
            record.windowStart = now;
            record.failures = 0;
        }

        record.failures++;

        if (record.failures >= MAX_ATTEMPTS) {
            record.lockedUntil = now + TimeUnit.SECONDS.toMillis(LOCK_DURATION_SECONDS);
            log.warn("登录暴力破解封锁: IP={}, 锁定至 {}",
                    clientIp, new java.util.Date(record.lockedUntil));
        }
    }

    public void clearOnSuccess(String clientIp) {
        records.remove(clientIp);
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        records.entrySet().removeIf(entry -> {
            AttemptRecord r = entry.getValue();
            return r.lockedUntil < now
                    && (now - r.windowStart) > TimeUnit.SECONDS.toMillis(WINDOW_SECONDS + 60);
        });
    }

    private static class AttemptRecord {
        long windowStart;       // 窗口起始时间
        int failures;           // 失败次数
        long lockedUntil;       // 锁定截止时间 (0 = 未锁定)

        AttemptRecord(long now) {
            this.windowStart = now;
            this.failures = 0;
            this.lockedUntil = 0;
        }
    }
}