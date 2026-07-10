package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LocalWindowRateLimiter {

    private final RateLimiterProperties properties;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public LocalWindowRateLimiter(RateLimiterProperties properties) {
        this.properties = properties;
    }

    public RateLimitDecision tryConsume(HttpServletRequest request, String namespace) {
        String key = buildKey(request, namespace);
        int requestsPerWindow = GatewayRequestContext.rateLimitRequests(request, properties);
        Duration windowDuration = Duration.ofSeconds(GatewayRequestContext.rateLimitWindowSeconds(request, properties));
        Window window = windows.computeIfAbsent(key, ignored -> new Window(Instant.now(), new AtomicInteger(0)));

        synchronized (window) {
            Instant now = Instant.now();
            if (!now.isBefore(window.windowStart.plus(windowDuration))) {
                window.windowStart = now;
                window.counter.set(0);
            }

            int current = window.counter.incrementAndGet();
            boolean allowed = current <= requestsPerWindow;
            int remaining = Math.max(0, requestsPerWindow - current);
            long elapsedSeconds = Duration.between(window.windowStart, now).getSeconds();
            long resetSeconds = Math.max(0, windowDuration.getSeconds() - elapsedSeconds);

            return new RateLimitDecision(allowed, remaining, resetSeconds, requestsPerWindow);
        }
    }

    private String buildKey(HttpServletRequest request, String namespace) {
        return String.format("local:%s:%s", namespace, GatewayRequestContext.rateLimitKey(request));
    }

    private static class Window {
        private Instant windowStart;
        private final AtomicInteger counter;

        private Window(Instant windowStart, AtomicInteger counter) {
            this.windowStart = windowStart;
            this.counter = counter;
        }
    }
}
