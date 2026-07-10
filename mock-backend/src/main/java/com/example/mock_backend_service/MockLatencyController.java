package com.example.mock_backend_service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MockLatencyController {

    @GetMapping("/hello")
    public ResponseEntity<Map<String, Object>> hello(@RequestParam(defaultValue = "0") long delayMs)
            throws InterruptedException {
        long safeDelayMs = Math.max(0, Math.min(delayMs, 10_000));
        long startedAt = System.nanoTime();

        if (safeDelayMs > 0) {
            Thread.sleep(safeDelayMs);
        }

        long backendLatencyMs = Math.round((System.nanoTime() - startedAt) / 1_000_000.0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Hello from mock backend");
        response.put("configuredDelayMs", safeDelayMs);
        response.put("backendLatencyMs", backendLatencyMs);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
