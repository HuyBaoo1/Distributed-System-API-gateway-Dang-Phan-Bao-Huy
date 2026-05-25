package com.example.apigateway.controller;

import com.example.apigateway.service.LatencyMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class LatencyMetricsController {

    private final LatencyMetricsService latencyMetricsService;

    public LatencyMetricsController(LatencyMetricsService latencyMetricsService) {
        this.latencyMetricsService = latencyMetricsService;
    }

    @GetMapping("/internal/latency/report")
    public Map<String, Object> report() {
        return latencyMetricsService.snapshot();
    }
}
