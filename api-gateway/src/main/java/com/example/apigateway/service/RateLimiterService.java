package com.example.apigateway.service;

import jakarta.servlet.http.HttpServletRequest;

public interface RateLimiterService {

    RateLimitDecision tryConsume(HttpServletRequest request);
}
