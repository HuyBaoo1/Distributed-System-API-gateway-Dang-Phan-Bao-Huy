package com.example.apigateway.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rate.limit.strategy", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryRateLimiterService implements RateLimiterService {

    private final LocalWindowRateLimiter localWindowRateLimiter;

    public InMemoryRateLimiterService(LocalWindowRateLimiter localWindowRateLimiter) {
        this.localWindowRateLimiter = localWindowRateLimiter;
    }

    @Override
    public RateLimitDecision tryConsume(HttpServletRequest request) {
        return localWindowRateLimiter.tryConsume(request, "in-memory");
    }
}
