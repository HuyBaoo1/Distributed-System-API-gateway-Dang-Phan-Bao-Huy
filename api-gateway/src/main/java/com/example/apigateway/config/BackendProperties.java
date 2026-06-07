package com.example.apigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "backend")
public class BackendProperties {

    private String baseUrl = "http://localhost:8081";
    private int connectTimeoutMs = 500;
    private int readTimeoutMs = 2_000;
    private int maxAttempts = 1;
    private int retryBackoffMs = 50;
    private boolean circuitBreakerEnabled = true;
    private int circuitBreakerFailureThreshold = 5;
    private int circuitBreakerResetTimeoutMs = 5_000;

    public String baseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int readTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int retryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public boolean circuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
    }

    public int circuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
    }

    public int circuitBreakerResetTimeoutMs() {
        return circuitBreakerResetTimeoutMs;
    }

    public void setCircuitBreakerResetTimeoutMs(int circuitBreakerResetTimeoutMs) {
        this.circuitBreakerResetTimeoutMs = circuitBreakerResetTimeoutMs;
    }
}
