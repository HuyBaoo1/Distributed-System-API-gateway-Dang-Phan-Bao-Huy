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

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public RedisScript<List> slidingWindowRateLimitScript() {
        return new DefaultRedisScript<>(SLIDING_WINDOW_LUA, List.class);
    }
}
