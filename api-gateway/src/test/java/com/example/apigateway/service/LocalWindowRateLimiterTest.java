package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LocalWindowRateLimiterTest {

    @Test
    void enforcesLimitPerClientRouteAndNamespace() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setRequestsPerMinute(2);
        properties.setWindowSeconds(60);
        LocalWindowRateLimiter limiter = new LocalWindowRateLimiter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.setRemoteAddr("198.51.100.20");

        RateLimitDecision first = limiter.tryConsume(request, "test");
        RateLimitDecision second = limiter.tryConsume(request, "test");
        RateLimitDecision third = limiter.tryConsume(request, "test");

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(0);
        assertThat(third.allowed()).isFalse();
        assertThat(third.limit()).isEqualTo(2);
    }
}
