package com.example.apigateway.service;

public record RateLimitDecision(boolean allowed, int remaining, long resetSeconds, int limit) {
}
