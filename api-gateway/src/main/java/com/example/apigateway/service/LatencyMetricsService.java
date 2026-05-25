package com.example.apigateway.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LatencyMetricsService {

    private static final int MAX_SAMPLES_PER_KEY = 2_000;

    private final ConcurrentHashMap<String, LatencyWindow> windows = new ConcurrentHashMap<>();
    private final Instant startedAt = Instant.now();

    public void record(String component, String route, int status, long elapsedNanos) {
        String normalizedRoute = normalizeRoute(route);
        String statusClass = status <= 0 ? "unknown" : (status / 100) + "xx";
        String key = component + "|" + normalizedRoute + "|" + statusClass;
        windows.computeIfAbsent(key, ignored -> new LatencyWindow(component, normalizedRoute, statusClass))
                .record(elapsedNanos);
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> metrics = windows.values().stream()
                .map(LatencyWindow::snapshot)
                .sorted(Comparator.comparing(value -> String.valueOf(value.get("component"))))
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startedAt", startedAt.toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("sampleWindowPerMetric", MAX_SAMPLES_PER_KEY);
        payload.put("metrics", metrics);
        return payload;
    }

    private String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return "unknown";
        }
        if (route.startsWith("/api/v1")) {
            return "/api/v1/**";
        }
        return route;
    }

    private static class LatencyWindow {
        private final String component;
        private final String route;
        private final String statusClass;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final ArrayDeque<Long> samples = new ArrayDeque<>();

        private LatencyWindow(String component, String route, String statusClass) {
            this.component = component;
            this.route = route;
            this.statusClass = statusClass;
        }

        private synchronized void record(long elapsedNanos) {
            count.incrementAndGet();
            totalNanos.addAndGet(elapsedNanos);
            maxNanos.accumulateAndGet(elapsedNanos, Math::max);
            samples.addLast(elapsedNanos);
            while (samples.size() > MAX_SAMPLES_PER_KEY) {
                samples.removeFirst();
            }
        }

        private synchronized Map<String, Object> snapshot() {
            List<Long> sortedSamples = new ArrayList<>(samples);
            sortedSamples.sort(Long::compareTo);

            long currentCount = count.get();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("component", component);
            value.put("route", route);
            value.put("statusClass", statusClass);
            value.put("count", currentCount);
            value.put("avgMs", toMillis(currentCount == 0 ? 0 : totalNanos.get() / currentCount));
            value.put("p50Ms", toMillis(percentile(sortedSamples, 0.50)));
            value.put("p95Ms", toMillis(percentile(sortedSamples, 0.95)));
            value.put("p99Ms", toMillis(percentile(sortedSamples, 0.99)));
            value.put("maxMs", toMillis(maxNanos.get()));
            return value;
        }

        private long percentile(List<Long> sortedSamples, double percentile) {
            if (sortedSamples.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(percentile * sortedSamples.size()) - 1;
            int safeIndex = Math.max(0, Math.min(index, sortedSamples.size() - 1));
            return sortedSamples.get(safeIndex);
        }

        private double toMillis(long nanos) {
            return LatencyFormatter.roundedMillis(nanos);
        }
    }
}
