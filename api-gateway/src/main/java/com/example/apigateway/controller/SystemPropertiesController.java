package com.example.apigateway.controller;

import com.example.apigateway.config.BackendProperties;
import com.example.apigateway.config.RateLimiterProperties;
import com.example.apigateway.service.BackendCircuitBreaker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SystemPropertiesController {

    private final RateLimiterProperties rateLimiterProperties;
    private final BackendProperties backendProperties;
    private final BackendCircuitBreaker backendCircuitBreaker;

    public SystemPropertiesController(RateLimiterProperties rateLimiterProperties,
                                      BackendProperties backendProperties,
                                      BackendCircuitBreaker backendCircuitBreaker) {
        this.rateLimiterProperties = rateLimiterProperties;
        this.backendProperties = backendProperties;
        this.backendCircuitBreaker = backendCircuitBreaker;
    }

    @GetMapping("/internal/system/properties")
    public Map<String, Object> properties() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("rateLimiting", rateLimiting());
        payload.put("backend", backend());
        return payload;
    }

    private Map<String, Object> rateLimiting() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("strategy", rateLimiterProperties.strategy());
        value.put("requestsPerMinute", rateLimiterProperties.requestsPerMinute());
        value.put("windowSeconds", rateLimiterProperties.windowSeconds());
        value.put("redisFailurePolicy", rateLimiterProperties.redisFailurePolicy());
        return value;
    }

    private Map<String, Object> backend() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("baseUrl", backendProperties.baseUrl());
        value.put("connectTimeoutMs", backendProperties.connectTimeoutMs());
        value.put("readTimeoutMs", backendProperties.readTimeoutMs());
        value.put("maxAttempts", backendProperties.maxAttempts());
        value.put("retryBackoffMs", backendProperties.retryBackoffMs());
        value.put("circuitBreaker", backendCircuitBreaker.snapshot());
        return value;
    }
}
