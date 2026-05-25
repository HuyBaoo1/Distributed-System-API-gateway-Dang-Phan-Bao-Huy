package com.example.apigateway.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyMetricsServiceTest {

    @Test
    void recordsLatencyPercentilesByComponentRouteAndStatusClass() {
        LatencyMetricsService service = new LatencyMetricsService();

        service.record("gateway_total", "/api/v1/hello", 200, 1_000_000);
        service.record("gateway_total", "/api/v1/hello", 200, 3_000_000);
        service.record("rate_limiter", "/api/v1/hello", 429, 2_000_000);

        Map<String, Object> snapshot = service.snapshot();
        List<Map<String, Object>> metrics = metricsFrom(snapshot);

        assertThat(metrics).hasSize(2);
        assertThat(metrics).anySatisfy(metric -> {
            assertThat(metric).containsEntry("component", "gateway_total");
            assertThat(metric).containsEntry("route", "/api/v1/**");
            assertThat(metric).containsEntry("statusClass", "2xx");
            assertThat(metric).containsEntry("count", 2L);
            assertThat(metric).containsEntry("avgMs", 2.0);
            assertThat(metric).containsEntry("p95Ms", 3.0);
        });
        assertThat(metrics).anySatisfy(metric -> {
            assertThat(metric).containsEntry("component", "rate_limiter");
            assertThat(metric).containsEntry("statusClass", "4xx");
            assertThat(metric).containsEntry("maxMs", 2.0);
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> metricsFrom(Map<String, Object> snapshot) {
        return (List<Map<String, Object>>) snapshot.get("metrics");
    }
}
