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
        long nowMillis = System.currentTimeMillis();
        long windowMillis = properties.windowSeconds() * 1000L;
        long windowStartMillis = nowMillis - (nowMillis % windowMillis);
        long ttlMillis = Math.max(1, windowStartMillis + windowMillis - nowMillis);
        String key = buildKey(request, windowStartMillis);

        try {
            List<String> result = executeFixedWindowScript(key, properties.requestsPerMinute(), ttlMillis);
            if (result == null || result.size() < 3) {
                return redisFailureHandler.onRedisFailure(request);
            }

            boolean allowed = "1".equals(result.get(0));
            int remaining = Integer.parseInt(result.get(1));
            long resetMillis = Long.parseLong(result.get(2));

            return new RateLimitDecision(allowed, remaining, millisToCeilSeconds(resetMillis), properties.requestsPerMinute());
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
        String clientIp = ClientIdentity.from(request);
        return String.format("distributed:fixed-window:%s:%s:%d", clientIp, request.getRequestURI(), windowStartMillis);
    }

    private long millisToCeilSeconds(long resetMillis) {
        return Math.max(0, (long) Math.ceil(resetMillis / 1000.0));
    }
}
