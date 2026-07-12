package com.example.apigateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyFormatterTest {

    @Test
    void formatsNanosecondsToMillisecondsString() {
        assertEquals("1.50", LatencyFormatter.millis(1_500_000));
        assertEquals("0.00", LatencyFormatter.millis(0));
    }

    @Test
    void roundsNanosecondsToTwoDecimalPlaces() {
        assertEquals(1.5, LatencyFormatter.roundedMillis(1_500_000), 0.0001);
        assertEquals(0.0, LatencyFormatter.roundedMillis(0), 0.0001);
    }
}
