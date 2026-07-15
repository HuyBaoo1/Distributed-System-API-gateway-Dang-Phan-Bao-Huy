package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class RateLimitMetricsService {

    private final MeterRegistry meterRegistry;
    private final RateLimiterProperties properties;
    private final String gatewayInstanceId;

    public RateLimitMetricsService(MeterRegistry meterRegistry,
                                   RateLimiterProperties properties,
                                   @Value("${gateshield.instance-id:local}") String gatewayInstanceId) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.gatewayInstanceId = sanitize(gatewayInstanceId);
    }

    public String gatewayInstanceId() {
        return gatewayInstanceId;
    }

    public void record(String routeId, RateLimitDecision decision, long decisionNanos) {
        Tags tags = Tags.of(
                "strategy", sanitize(properties.strategy()),
                "route", sanitize(routeId),
                "gateway_instance", gatewayInstanceId,
                "decision", decision.allowed() ? "allowed" : "rejected"
        );
        meterRegistry.counter("gateshield_rate_limit_decisions_total", tags).increment();
        meterRegistry.timer("gateshield_rate_limit_decision_latency", tags)
                .record(decisionNanos, TimeUnit.NANOSECONDS);
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
