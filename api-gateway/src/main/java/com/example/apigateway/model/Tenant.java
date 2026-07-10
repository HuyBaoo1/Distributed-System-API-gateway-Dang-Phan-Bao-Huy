package com.example.apigateway.model;

import java.time.Instant;

public record Tenant(
        String id,
        String name,
        String apiKeyHash,
        String planName,
        boolean enabled,
        Instant createdAt
) {
}
