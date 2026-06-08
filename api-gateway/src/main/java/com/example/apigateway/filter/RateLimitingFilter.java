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
import org.springframework.web.util.ContentCachingResponseWrapper;

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

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long gatewayStartNanos = System.nanoTime();
        long rateLimiterStartNanos = System.nanoTime();
        RateLimitDecision decision = rateLimiterService.tryConsume(request);
        long rateLimiterNanos = System.nanoTime() - rateLimiterStartNanos;

        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_LIMIT, String.valueOf(decision.limit()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_REMAINING, String.valueOf(decision.remaining()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_RESET, String.valueOf(decision.resetSeconds()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_LATENCY_MS, LatencyFormatter.millis(rateLimiterNanos));
        latencyMetricsService.record("rate_limiter", request.getRequestURI(), decision.allowed() ? 200 : 429, rateLimiterNanos);

        if (!decision.allowed()) {
            int tooManyRequestsStatus = HttpStatus.TOO_MANY_REQUESTS.value();
            wrappedResponse.setStatus(tooManyRequestsStatus);
            wrappedResponse.setHeader(GatewayHeaders.RETRY_AFTER, String.valueOf(decision.resetSeconds()));
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrappedResponse.getWriter().write("{\"error\":\"Distributed rate limit exceeded. Retry after " + decision.resetSeconds() + " seconds.\"}");
            long gatewayNanos = System.nanoTime() - gatewayStartNanos;
            wrappedResponse.setHeader(GatewayHeaders.GATEWAY_LATENCY_MS, LatencyFormatter.millis(gatewayNanos));
            latencyMetricsService.record("gateway_total", request.getRequestURI(), tooManyRequestsStatus, gatewayNanos);
            wrappedResponse.copyBodyToResponse();
            return;
        }

        try {
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            long gatewayNanos = System.nanoTime() - gatewayStartNanos;
            wrappedResponse.setHeader(GatewayHeaders.GATEWAY_LATENCY_MS, LatencyFormatter.millis(gatewayNanos));
            latencyMetricsService.record("gateway_total", request.getRequestURI(), wrappedResponse.getStatus(), gatewayNanos);
            wrappedResponse.copyBodyToResponse();
        }
    }
}
