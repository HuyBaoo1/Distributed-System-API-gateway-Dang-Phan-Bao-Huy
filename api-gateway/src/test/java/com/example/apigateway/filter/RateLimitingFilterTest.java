package com.example.apigateway.filter;

import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.model.Tenant;
import com.example.apigateway.service.GatewayRouteService;
import com.example.apigateway.service.LatencyMetricsService;
import com.example.apigateway.service.RateLimitDecision;
import com.example.apigateway.service.RateLimitMetricsService;
import com.example.apigateway.service.RateLimiterService;
import com.example.apigateway.service.RequestLogService;
import com.example.apigateway.service.TenantService;
import com.example.apigateway.config.RateLimiterProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitingFilterTest {

    @Test
    void addsLatencyHeadersWhenRequestIsAllowed() throws Exception {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RateLimitDecision(true, 9, 60, 10));

        RateLimitingFilter filter = filter(rateLimiterService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.addHeader(GatewayHeaders.API_KEY, "test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_LIMIT)).isEqualTo("10");
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_REMAINING)).isEqualTo("9");
        assertThat(response.getHeader(GatewayHeaders.RATE_LIMIT_LATENCY_MS)).isNotBlank();
        assertThat(response.getHeader(GatewayHeaders.GATEWAY_LATENCY_MS)).isNotBlank();
        assertThat(response.getHeader(GatewayHeaders.GATEWAY_INSTANCE_ID)).isEqualTo("test-instance");
    }

    @Test
    void returnsTooManyRequestsWhenLimitIsExceeded() throws Exception {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.tryConsume(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RateLimitDecision(false, 0, 12, 10));

        RateLimitingFilter filter = filter(rateLimiterService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.addHeader(GatewayHeaders.API_KEY, "test-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(GatewayHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(response.getHeader(GatewayHeaders.GATEWAY_LATENCY_MS)).isNotBlank();
        assertThat(response.getContentAsString()).contains("Distributed rate limit exceeded");
    }

    @Test
    void returnsUnauthorizedWhenApiKeyIsMissing() throws Exception {
        RateLimitingFilter filter = filter(mock(RateLimiterService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("valid X-API-Key required");
    }

    @Test
    void returnsUnauthorizedWhenApiKeyIsInvalid() throws Exception {
        GatewayRouteService routeService = mock(GatewayRouteService.class);
        when(routeService.match("/api/v1/hello")).thenReturn(Optional.of(route()));
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.authenticate("bad-key")).thenReturn(Optional.empty());
        RateLimitingFilter filter = new RateLimitingFilter(
                routeService,
                tenantService,
                mock(RateLimiterService.class),
                testMetricsService(),
                new LatencyMetricsService(),
                mock(RequestLogService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.addHeader(GatewayHeaders.API_KEY, "bad-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private RateLimitingFilter filter(RateLimiterService rateLimiterService) {
        GatewayRouteService routeService = mock(GatewayRouteService.class);
        when(routeService.match("/api/v1/hello")).thenReturn(Optional.of(route()));
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.authenticate("test-key")).thenReturn(Optional.of(tenant()));
        return new RateLimitingFilter(
                routeService,
                tenantService,
                rateLimiterService,
                testMetricsService(),
                new LatencyMetricsService(),
                mock(RequestLogService.class));
    }

    private RateLimitMetricsService testMetricsService() {
        return new RateLimitMetricsService(new SimpleMeterRegistry(), new RateLimiterProperties(), "test-instance");
    }

    private GatewayRoute route() {
        return new GatewayRoute(
                "mock-api",
                "/api/v1/**",
                "http://backend:8081",
                Set.of(HttpMethod.GET),
                true,
                10,
                60,
                Instant.now(),
                Instant.now());
    }

    private Tenant tenant() {
        return new Tenant("tenant-a", "Tenant A", "hash", "free", true, Instant.now());
    }
}
