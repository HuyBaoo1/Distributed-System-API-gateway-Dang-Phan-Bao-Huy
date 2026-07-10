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
@ConditionalOnProperty(name = "rate.limit.strategy", havingValue = "redis-token-bucket")
public class RedisTokenBucketRateLimiterService implements RateLimiterService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> tokenBucketRateLimitScript;
    private final RateLimiterProperties properties;
    private final RedisFailureHandler redisFailureHandler;

    public RedisTokenBucketRateLimiterService(StringRedisTemplate redisTemplate,
                                              @Qualifier("tokenBucketRateLimitScript")
                                              RedisScript<List> tokenBucketRateLimitScript,
                                              RateLimiterProperties properties,
                                              RedisFailureHandler redisFailureHandler) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketRateLimitScript = tokenBucketRateLimitScript;
        this.properties = properties;
        this.redisFailureHandler = redisFailureHandler;
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        String key = GatewayRequestContext.rateLimitKey(request);
        int requestLimit = GatewayRequestContext.rateLimitRequests(request, properties);
        int windowSeconds = GatewayRequestContext.rateLimitWindowSeconds(request, properties);
        long nowMillis = System.currentTimeMillis();
        long refillPeriodMillis = windowSeconds * 1000L;

        try {
            List<String> result = executeTokenBucketScript(
                    key,
                    requestLimit,
                    nowMillis,
                    refillPeriodMillis
            );

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
    private List<String> executeTokenBucketScript(String key,
                                                  int requestLimit,
                                                  long nowMillis,
                                                  long refillPeriodMillis) {
        return redisTemplate.execute(
                tokenBucketRateLimitScript,
                Collections.singletonList(key),
                String.valueOf(requestLimit),
                String.valueOf(nowMillis),
                String.valueOf(refillPeriodMillis)
        );
    }

    private long millisToCeilSeconds(long resetMillis) {
        return Math.max(0, (long) Math.ceil(resetMillis / 1000.0));
    }
}
