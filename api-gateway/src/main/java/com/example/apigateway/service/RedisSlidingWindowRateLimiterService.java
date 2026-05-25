package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
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

    public RedisSlidingWindowRateLimiterService(StringRedisTemplate redisTemplate,
                                                RedisScript<List> slidingWindowRateLimitScript,
                                                RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowRateLimitScript = slidingWindowRateLimitScript;
        this.properties = properties;
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        String key = buildKey(request);
        long nowMillis = System.currentTimeMillis();
        long windowMillis = properties.windowSeconds() * 1000L;
        long minimumAllowedTimestamp = nowMillis - windowMillis;
        String memberId = UUID.randomUUID().toString();

        List<String> result = executeSlidingWindowScript(
                key,
                properties.requestsPerMinute(),
                minimumAllowedTimestamp,
                nowMillis,
                memberId,
                windowMillis
        );

        if (result == null || result.size() < 3) {
            return new RateLimitDecision(false, 0, properties.windowSeconds(), properties.requestsPerMinute());
        }

        boolean allowed = "1".equals(result.get(0));
        int remaining = Integer.parseInt(result.get(1));
        long resetMillis = Long.parseLong(result.get(2));
        long resetSeconds = Math.max(0, resetMillis / 1000L);

        return new RateLimitDecision(allowed, remaining, resetSeconds, properties.requestsPerMinute());
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

    private String buildKey(HttpServletRequest request) {
        String clientIp = ClientIdentity.from(request);
        return String.format("distributed:rate:%s:%s", clientIp, request.getRequestURI());
    }
}
