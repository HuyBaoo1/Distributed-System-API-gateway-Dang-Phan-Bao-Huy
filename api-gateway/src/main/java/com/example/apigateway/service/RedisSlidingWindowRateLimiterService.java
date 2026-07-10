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
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "rate.limit.strategy", havingValue = "redis-sliding-window")
public class RedisSlidingWindowRateLimiterService implements RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> slidingWindowRateLimitScript;
    private final RateLimiterProperties properties;
    private final RedisFailureHandler redisFailureHandler;

    public RedisSlidingWindowRateLimiterService(StringRedisTemplate redisTemplate,
                                                @Qualifier("slidingWindowRateLimitScript")
                                                RedisScript<List> slidingWindowRateLimitScript,
                                                RateLimiterProperties properties,
                                                RedisFailureHandler redisFailureHandler) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowRateLimitScript = slidingWindowRateLimitScript;
        this.properties = properties;
        this.redisFailureHandler = redisFailureHandler;
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        String key = GatewayRequestContext.rateLimitKey(request);
        int requestLimit = GatewayRequestContext.rateLimitRequests(request, properties);
        int windowSeconds = GatewayRequestContext.rateLimitWindowSeconds(request, properties);
        long nowMillis = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        long minimumAllowedTimestamp = nowMillis - windowMillis;
        String memberId = UUID.randomUUID().toString();

        try {
            List<String> result = executeSlidingWindowScript(
                    key,
                    requestLimit,
                    minimumAllowedTimestamp,
                    nowMillis,
                    memberId,
                    windowMillis
            );

            if (result == null || result.size() < 3) {
                return redisFailureHandler.onRedisFailure(request);
            }

            boolean allowed = "1".equals(result.get(0));
            int remaining = Integer.parseInt(result.get(1));
            long resetMillis = Long.parseLong(result.get(2));
            long resetSeconds = millisToCeilSeconds(resetMillis);

            return new RateLimitDecision(allowed, remaining, resetSeconds, requestLimit);
        } catch (RuntimeException ex) {
            return redisFailureHandler.onRedisFailure(request);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> executeSlidingWindowScript(String key,
                                                    int requestLimit,
                                                    long minimumAllowedTimestamp,
                                                    long nowMillis,
                                                    String memberId,
                                                    long windowMillis) {
        return redisTemplate.execute(
                slidingWindowRateLimitScript,
                Collections.singletonList(key),
                String.valueOf(requestLimit),
                String.valueOf(minimumAllowedTimestamp),
                String.valueOf(nowMillis),
                memberId,
                String.valueOf(windowMillis)
        );
    }

    private long millisToCeilSeconds(long resetMillis) {
        return Math.max(0, (long) Math.ceil(resetMillis / 1000.0));
    }
}
