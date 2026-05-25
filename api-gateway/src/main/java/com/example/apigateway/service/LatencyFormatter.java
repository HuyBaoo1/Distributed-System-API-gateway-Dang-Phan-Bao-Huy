package com.example.apigateway.service;

import java.util.Locale;

public final class LatencyFormatter {

    private LatencyFormatter() {
    }

    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    public static double roundedMillis(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 100.0) / 100.0;
    }
}
