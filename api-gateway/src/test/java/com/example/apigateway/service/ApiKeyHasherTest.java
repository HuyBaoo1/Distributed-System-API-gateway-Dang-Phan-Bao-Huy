package com.example.apigateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyHasherTest {

    private final ApiKeyHasher hasher = new ApiKeyHasher();

    @Test
    void hashRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash("   "));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    }

    @Test
    void hashAndMatchesRoundTrip() {
        String apiKey = "tenant-123";
        String hash = hasher.hash(apiKey);

        assertTrue(hasher.matches(apiKey, hash));
        assertFalse(hasher.matches("tenant-456", hash));
    }

    @Test
    void generateApiKeyProducesPrefixedHexValue() {
        String apiKey = assertDoesNotThrow(hasher::generateApiKey);

        assertTrue(apiKey.startsWith("gs_"));
        assertTrue(apiKey.length() > 3);
    }
}
