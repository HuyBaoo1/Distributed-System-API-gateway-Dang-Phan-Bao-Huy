package com.example.apigateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class GatewayConfiguration {

    private static final String SLIDING_WINDOW_LUA = "local limit = tonumber(ARGV[1])\n" +
            "local minScore = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "local member = ARGV[4]\n" +
            "local windowMillis = tonumber(ARGV[5])\n" +
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, minScore)\n" +
            "local current = tonumber(redis.call('ZCARD', KEYS[1]))\n" +
            "if current < limit then\n" +
            "  redis.call('ZADD', KEYS[1], now, member)\n" +
            "  redis.call('PEXPIRE', KEYS[1], windowMillis)\n" +
            "  return {'1', tostring(limit - current - 1), tostring(windowMillis)}\n" +
            "end\n" +
            "local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')\n" +
            "local reset = tonumber(oldest[2]) + windowMillis - now\n" +
            "return {'0', '0', tostring(reset)}";

    private static final String FIXED_WINDOW_LUA = "local limit = tonumber(ARGV[1])\n" +
            "local ttlMillis = tonumber(ARGV[2])\n" +
            "local current = tonumber(redis.call('INCR', KEYS[1]))\n" +
            "if current == 1 then\n" +
            "  redis.call('PEXPIRE', KEYS[1], ttlMillis)\n" +
            "end\n" +
            "local ttl = tonumber(redis.call('PTTL', KEYS[1]))\n" +
            "if ttl < 0 then\n" +
            "  ttl = ttlMillis\n" +
            "end\n" +
            "if current <= limit then\n" +
            "  return {'1', tostring(limit - current), tostring(ttl)}\n" +
            "end\n" +
            "return {'0', '0', tostring(ttl)}";

    private static final String TOKEN_BUCKET_LUA = "local capacity = tonumber(ARGV[1])\n" +
            "local now = tonumber(ARGV[2])\n" +
            "local refillPeriodMillis = tonumber(ARGV[3])\n" +
            "local requested = 1\n" +
            "local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updatedAt')\n" +
            "local tokens = tonumber(bucket[1])\n" +
            "local updatedAt = tonumber(bucket[2])\n" +
            "if tokens == nil then\n" +
            "  tokens = capacity\n" +
            "end\n" +
            "if updatedAt == nil then\n" +
            "  updatedAt = now\n" +
            "end\n" +
            "local elapsed = math.max(0, now - updatedAt)\n" +
            "local refill = elapsed * capacity / refillPeriodMillis\n" +
            "tokens = math.min(capacity, tokens + refill)\n" +
            "local allowed = 0\n" +
            "local reset = 0\n" +
            "if tokens >= requested then\n" +
            "  tokens = tokens - requested\n" +
            "  allowed = 1\n" +
            "else\n" +
            "  local missing = requested - tokens\n" +
            "  reset = math.ceil(missing * refillPeriodMillis / capacity)\n" +
            "end\n" +
            "redis.call('HMSET', KEYS[1], 'tokens', tostring(tokens), 'updatedAt', tostring(now))\n" +
            "redis.call('PEXPIRE', KEYS[1], refillPeriodMillis * 2)\n" +
            "return {tostring(allowed), tostring(math.floor(tokens)), tostring(reset)}";

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> slidingWindowRateLimitScript() {
        return new DefaultRedisScript<>(SLIDING_WINDOW_LUA, List.class);
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> fixedWindowRateLimitScript() {
        return new DefaultRedisScript<>(FIXED_WINDOW_LUA, List.class);
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> tokenBucketRateLimitScript() {
        return new DefaultRedisScript<>(TOKEN_BUCKET_LUA, List.class);
    }
}
