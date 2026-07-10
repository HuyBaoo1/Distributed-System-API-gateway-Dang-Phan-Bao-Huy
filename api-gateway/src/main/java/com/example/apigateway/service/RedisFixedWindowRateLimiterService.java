package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@ConditionalOnProperty(name = "rate.limit.strategy", havingValue = "redis-fixed-window")
public class RedisFixedWindowRateLimiterService implements RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> fixedWindowRateLimitScript;
    private final RateLimiterProperties properties;
    private final RedisFailureHandler redisFailureHandler;

    public RedisFixedWindowRateLimiterService(StringRedisTemplate redisTemplate,
                                              @Qualifier("fixedWindowRateLimitScript")
                                              RedisScript<List> fixedWindowRateLimitScript,
                                              RateLimiterProperties properties,
                                              RedisFailureHandler redisFailureHandler) {
        this.redisTemplate = redisTemplate;
        this.fixedWindowRateLimitScript = fixedWindowRateLimitScript;
        this.properties = properties;
        this.redisFailureHandler = redisFailureHandler;
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        int requestLimit = GatewayRequestContext.rateLimitRequests(request, properties);
        int windowSeconds = GatewayRequestContext.rateLimitWindowSeconds(request, properties);
        long nowMillis = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        long windowStartMillis = nowMillis - (nowMillis % windowMillis);
        long ttlMillis = Math.max(1, windowStartMillis + windowMillis - nowMillis);
        String key = buildKey(request, windowStartMillis);

        try {
            List<String> result = executeFixedWindowScript(key, requestLimit, ttlMillis);
            if (result == null || result.size() < 3) {
                return redisFailureHandler.onRedisFailure(request);
            }

            boolean allowed = "1".equals(result.get(0));
            int remaining = Integer.parseInt(result.get(1));
            long resetMillis = Long.parseLong(result.get(2));

            return new RateLimitDecision(allowed, remaining, millisToCeilSeconds(resetMillis), requestLimit);
        } catch (RuntimeException ex) {
            return redisFailureHandler.onRedisFailure(request);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> executeFixedWindowScript(String key, int requestLimit, long ttlMillis) {
        return redisTemplate.execute(
                fixedWindowRateLimitScript,
                Collections.singletonList(key),
                String.valueOf(requestLimit),
                String.valueOf(ttlMillis)
        );
    }

    private String buildKey(HttpServletRequest request, long windowStartMillis) {
        return String.format("%s:%d", GatewayRequestContext.rateLimitKey(request), windowStartMillis);
    }

    private long millisToCeilSeconds(long resetMillis) {
        return Math.max(0, (long) Math.ceil(resetMillis / 1000.0));
    }
}
