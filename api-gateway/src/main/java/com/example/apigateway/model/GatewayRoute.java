package com.example.apigateway.model;

import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Set;

public record GatewayRoute(
        String routeId,
        String pathPattern,
        String targetUrl,
        Set<HttpMethod> allowedMethods,
        boolean enabled,
        int rateLimitRequests,
        int rateLimitWindowSeconds,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean allows(HttpMethod method) {
        return allowedMethods.contains(method);
    }
}
