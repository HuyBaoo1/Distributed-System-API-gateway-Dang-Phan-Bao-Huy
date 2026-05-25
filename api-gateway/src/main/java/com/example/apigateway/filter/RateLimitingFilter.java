package com.example.apigateway.filter;

import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.service.LatencyFormatter;
import com.example.apigateway.service.LatencyMetricsService;
import com.example.apigateway.service.RateLimitDecision;
import com.example.apigateway.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;
    private final LatencyMetricsService latencyMetricsService;

    public RateLimitingFilter(RateLimiterService rateLimiterService,
                              LatencyMetricsService latencyMetricsService) {
        this.rateLimiterService = rateLimiterService;
        this.latencyMetricsService = latencyMetricsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/v1")) {
            filterChain.doFilter(request, response);
            return;
        }

        long gatewayStartNanos = System.nanoTime();
        long rateLimiterStartNanos = System.nanoTime();
        RateLimitDecision decision = rateLimiterService.tryConsume(request);
        long rateLimiterNanos = System.nanoTime() - rateLimiterStartNanos;

        response.setHeader(GatewayHeaders.RATE_LIMIT_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(GatewayHeaders.RATE_LIMIT_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(GatewayHeaders.RATE_LIMIT_RESET, String.valueOf(decision.resetSeconds()));
        response.setHeader(GatewayHeaders.RATE_LIMIT_LATENCY_MS, LatencyFormatter.millis(rateLimiterNanos));
        latencyMetricsService.record("rate_limiter", request.getRequestURI(), decision.allowed() ? 200 : 429, rateLimiterNanos);

        if (!decision.allowed()) {
            int tooManyRequestsStatus = HttpStatus.TOO_MANY_REQUESTS.value();
            response.setStatus(tooManyRequestsStatus);
            response.setHeader(GatewayHeaders.RETRY_AFTER, String.valueOf(decision.resetSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Distributed rate limit exceeded. Retry after " + decision.resetSeconds() + " seconds.\"}");
            long gatewayNanos = System.nanoTime() - gatewayStartNanos;
            response.setHeader(GatewayHeaders.GATEWAY_LATENCY_MS, LatencyFormatter.millis(gatewayNanos));
            latencyMetricsService.record("gateway_total", request.getRequestURI(), tooManyRequestsStatus, gatewayNanos);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            long gatewayNanos = System.nanoTime() - gatewayStartNanos;
            if (!response.isCommitted()) {
                response.setHeader(GatewayHeaders.GATEWAY_LATENCY_MS, LatencyFormatter.millis(gatewayNanos));
            }
            latencyMetricsService.record("gateway_total", request.getRequestURI(), response.getStatus(), gatewayNanos);
        }
    }
}
