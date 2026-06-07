package com.example.apigateway.service;

import com.example.apigateway.config.BackendProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BackendCircuitBreaker {

    private static final long CLOSED = -1L;

    private final BackendProperties properties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtMillis = new AtomicLong(CLOSED);

    public BackendCircuitBreaker(BackendProperties properties) {
        this.properties = properties;
    }

    public boolean allowRequest() {
        if (!properties.circuitBreakerEnabled()) {
            return true;
        }

        long openedAt = openedAtMillis.get();
        if (openedAt == CLOSED) {
            return true;
        }

        long resetTimeoutMs = Math.max(1, properties.circuitBreakerResetTimeoutMs());
        if (System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
            if (openedAtMillis.compareAndSet(openedAt, CLOSED)) {
                consecutiveFailures.set(0);
            }
            return true;
        }

        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAtMillis.set(CLOSED);
    }

    public void recordFailure() {
        if (!properties.circuitBreakerEnabled()) {
            return;
        }

        int threshold = Math.max(1, properties.circuitBreakerFailureThreshold());
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= threshold) {
            openedAtMillis.compareAndSet(CLOSED, System.currentTimeMillis());
        }
    }

    public void recordResult(int statusCode) {
        if (statusCode >= 500) {
            recordFailure();
        } else {
            recordSuccess();
        }
    }

    public String state() {
        if (!properties.circuitBreakerEnabled()) {
            return "disabled";
        }
        return openedAtMillis.get() == CLOSED ? "closed" : "open";
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", properties.circuitBreakerEnabled());
        payload.put("state", state());
        payload.put("consecutiveFailures", consecutiveFailures.get());
        payload.put("failureThreshold", Math.max(1, properties.circuitBreakerFailureThreshold()));
        payload.put("resetTimeoutMs", Math.max(1, properties.circuitBreakerResetTimeoutMs()));

        long openedAt = openedAtMillis.get();
        payload.put("openedAt", openedAt == CLOSED ? null : Instant.ofEpochMilli(openedAt).toString());
        return payload;
    }
}
