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
        int requestLimit = GatewayRequestContext.rateLimitRequests(request, properties);
        int windowSeconds = GatewayRequestContext.rateLimitWindowSeconds(request, properties);

        if (FAIL_OPEN.equals(policy)) {
            return new RateLimitDecision(true, requestLimit, 0, requestLimit);
        }

        if (LOCAL_FALLBACK.equals(policy)) {
            return localWindowRateLimiter.tryConsume(request, "redis-fallback");
        }

        return new RateLimitDecision(false, 0, windowSeconds, requestLimit);
    }
}
