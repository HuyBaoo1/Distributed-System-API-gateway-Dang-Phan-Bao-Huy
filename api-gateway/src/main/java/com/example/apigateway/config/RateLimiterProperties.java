package com.example.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate.limit")
public class RateLimiterProperties {

    private String strategy = "redis-sliding-window";
    private int requestsPerMinute = 60;
    private int windowSeconds = 60;
    private String redisFailurePolicy = "fail-closed";

    public String strategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public int requestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public String redisFailurePolicy() {
        return redisFailurePolicy;
    }

    public void setRedisFailurePolicy(String redisFailurePolicy) {
        this.redisFailurePolicy = redisFailurePolicy;
    }
}
