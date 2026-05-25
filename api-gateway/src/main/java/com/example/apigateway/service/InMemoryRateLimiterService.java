package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(name = "rate.limit.strategy", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRateLimiterService implements RateLimiterService {

    private final int requestsPerWindow;
    private final Duration windowDuration;
    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiterService(RateLimiterProperties properties) {
        this.requestsPerWindow = properties.requestsPerMinute();
        this.windowDuration = Duration.ofSeconds(properties.windowSeconds());
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        String key = buildKey(request);
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow(Instant.now(), new AtomicInteger(0)));

        synchronized (window) {
            Instant now = Instant.now();
            if (now.isAfter(window.windowStart.plus(windowDuration))) {
                window.windowStart = now;
                window.counter.set(0);
            }

            int current = window.counter.incrementAndGet();
            boolean allowed = current <= requestsPerWindow;
            int remaining = Math.max(0, requestsPerWindow - current);
            long resetSeconds = windowDuration.minusSeconds(now.getEpochSecond() - window.windowStart.getEpochSecond()).getSeconds();
            return new RateLimitDecision(allowed, remaining, resetSeconds, requestsPerWindow);
        }
    }

    private String buildKey(HttpServletRequest request) {
        String clientIp = ClientIdentity.from(request);
        return String.format("rate:%s:%s", clientIp, request.getRequestURI());
    }

    private static class SlidingWindow {
        private Instant windowStart;
        private final AtomicInteger counter;

        private SlidingWindow(Instant windowStart, AtomicInteger counter) {
            this.windowStart = windowStart;
            this.counter = counter;
        }
    }
}
