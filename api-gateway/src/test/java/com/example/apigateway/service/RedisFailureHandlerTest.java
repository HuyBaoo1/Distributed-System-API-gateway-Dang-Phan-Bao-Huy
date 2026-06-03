package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFailureHandlerTest {

    @Test
    void failOpenAllowsRequestsWhenRedisIsUnavailable() {
        RateLimiterProperties properties = properties("fail-open");
        RedisFailureHandler handler = new RedisFailureHandler(properties, new LocalWindowRateLimiter(properties));

        RateLimitDecision decision = handler.onRedisFailure(request());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(2);
        assertThat(decision.resetSeconds()).isZero();
    }

    @Test
    void failClosedRejectsRequestsWhenRedisIsUnavailable() {
        RateLimiterProperties properties = properties("fail-closed");
        RedisFailureHandler handler = new RedisFailureHandler(properties, new LocalWindowRateLimiter(properties));

        RateLimitDecision decision = handler.onRedisFailure(request());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.resetSeconds()).isEqualTo(60);
    }

    @Test
    void localFallbackUsesLocalLimitWhenRedisIsUnavailable() {
        RateLimiterProperties properties = properties("local-fallback");
        RedisFailureHandler handler = new RedisFailureHandler(properties, new LocalWindowRateLimiter(properties));
        MockHttpServletRequest request = request();

        RateLimitDecision first = handler.onRedisFailure(request);
        RateLimitDecision second = handler.onRedisFailure(request);
        RateLimitDecision third = handler.onRedisFailure(request);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
    }

    private RateLimiterProperties properties(String policy) {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setRequestsPerMinute(2);
        properties.setWindowSeconds(60);
        properties.setRedisFailurePolicy(policy);
        return properties;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.setRemoteAddr("198.51.100.30");
        return request;
    }
}
