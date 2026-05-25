package com.example.apigateway.http;

public final class GatewayHeaders {

    public static final String BACKEND_LATENCY_MS = "X-Backend-Latency-Ms";
    public static final String GATEWAY_LATENCY_MS = "X-Gateway-Latency-Ms";
    public static final String RATE_LIMIT_LATENCY_MS = "X-RateLimit-Latency-Ms";
    public static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    public static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";
    public static final String RETRY_AFTER = "Retry-After";
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private GatewayHeaders() {
    }
}
