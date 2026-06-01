package com.shorturl.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 短链接创建速率限制，防止短码空间被恶意耗尽。
 * 同一 IP 每分钟最多 60 次创建请求，超限后返回 429。
 */
@Component
public class ShortenRateLimiter {

    private static final int MAX_REQUESTS = 60;           // 每分钟最多 60 次
    private static final long WINDOW_SECONDS = 60;         // 窗口 60 秒
    private static final long CLEAN_INTERVAL_SECONDS = 120; // 清理过期条目间隔

    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private volatile long lastCleanTime = System.currentTimeMillis();

    /**
     * 原子地检查并消费一次请求配额。
     * 在同一个 synchronized 块内完成"检查→记录"，消除 TOCTOU 竞态。
     *
     * @param clientIp 客户端 IP
     * @return true = 允许本次请求（配额内）；false = 已超限，拒绝
     */
    public boolean tryConsume(String clientIp) {
        cleanIfNeeded();
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.computeIfAbsent(clientIp, k -> new WindowCounter(now));
        synchronized (counter) {
            long windowStart = counter.windowStart.get();
            if (now - windowStart > TimeUnit.SECONDS.toMillis(WINDOW_SECONDS)) {
                // 窗口过期，重置
                counter.windowStart.set(now);
                counter.count.set(1);
                return true;
            }
            if (counter.count.get() >= MAX_REQUESTS) {
                return false;
            }
            counter.count.incrementAndGet();
            return true;
        }
    }

    private void cleanIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanTime > TimeUnit.SECONDS.toMillis(CLEAN_INTERVAL_SECONDS)) {
            lastCleanTime = now;
            counters.entrySet().removeIf(entry -> {
                long windowStart = entry.getValue().windowStart.get();
                return (now - windowStart) > TimeUnit.SECONDS.toMillis(WINDOW_SECONDS + 120);
            });
        }
    }

    private static class WindowCounter {
        final AtomicInteger count;
        final AtomicLong windowStart;

        WindowCounter(long now) {
            this.count = new AtomicInteger(0);
            this.windowStart = new AtomicLong(now);
        }
    }
}