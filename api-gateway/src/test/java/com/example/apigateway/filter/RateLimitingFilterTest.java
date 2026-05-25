package com.example.apigateway.filter;

import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.service.LatencyMetricsService;
import com.example.apigateway.service.RateLimitDecision;
import com.example.apigateway.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitingFilterTest {

    @Test
    void addsLatencyHeadersWhenRequestIsAllowed() throws Exception {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RateLimitDecision(true, 9, 60, 10));

        RateLimitingFilter filter = new RateLimitingFilter(rateLimiterService, new LatencyMetricsService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isEqualTo("10");
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_REMAINING)).isEqualTo("9");
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_LATENCY_MS)).isNotBlank();
        assertThat(response.getHeader(GatewayHeaders.GATEWAY_LATENCY_MS)).isNotBlank();
    }

    @Test
    void returnsTooManyRequestsWhenLimitIsExceeded() throws Exception {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RateLimitDecision(false, 0, 12, 10));

        RateLimitingFilter filter = new RateLimitingFilter(rateLimiterService, new LatencyMetricsService());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(GatewayHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(response.getHeader(GatewayHeaders.GATEWAY_LATENCY_MS)).isNotBlank();
        assertThat(response.getContentAsString()).contains("Distributed rate limit exceeded");
    }
}
