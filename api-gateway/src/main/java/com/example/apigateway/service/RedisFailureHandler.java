package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RedisFailureHandler {

    private static final String FAIL_OPEN = "fail-open";
    private static final String LOCAL_FALLBACK = "local-fallback";

    private final RateLimiterProperties properties;
    private final LocalWindowRateLimiter localWindowRateLimiter;

    public RedisFailureHandler(RateLimiterProperties properties,
                               LocalWindowRateLimiter localWindowRateLimiter) {
        this.properties = properties;
        this.localWindowRateLimiter = localWindowRateLimiter;
    }

    public RateLimitDecision onRedisFailure(HttpServletRequest request) {
        String configuredPolicy = properties.redisFailurePolicy();
        String policy = configuredPolicy == null ? "" : configuredPolicy.trim().toLowerCase(Locale.ROOT);

        if (FAIL_OPEN.equals(policy)) {
            return new RateLimitDecision(true, properties.requestsPerMinute(), 0, properties.requestsPerMinute());
        }

        if (LOCAL_FALLBACK.equals(policy)) {
            return localWindowRateLimiter.tryConsume(request, "redis-fallback");
        }

        return new RateLimitDecision(false, 0, properties.windowSeconds(), properties.requestsPerMinute());
    }
}
