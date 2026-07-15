package com.example.apigateway.filter;

import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.model.Tenant;
import com.example.apigateway.service.ClientIdentity;
import com.example.apigateway.service.GatewayRequestContext;
import com.example.apigateway.service.GatewayRouteService;
import com.example.apigateway.service.LatencyFormatter;
import com.example.apigateway.service.LatencyMetricsService;
import com.example.apigateway.service.RateLimitDecision;
import com.example.apigateway.service.RateLimitMetricsService;
import com.example.apigateway.service.RateLimiterService;
import com.example.apigateway.service.RequestLogService;
import com.example.apigateway.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String RATE_LIMIT_ALLOWED = "allowed";
    private static final String RATE_LIMIT_REJECTED = "rejected";
    private static final String RATE_LIMIT_SKIPPED = "skipped";

    private final GatewayRouteService routeService;
    private final TenantService tenantService;
    private final RateLimiterService rateLimiterService;
    private final RateLimitMetricsService rateLimitMetricsService;
    private final LatencyMetricsService latencyMetricsService;
    private final RequestLogService requestLogService;

    public RateLimitingFilter(GatewayRouteService routeService,
                              TenantService tenantService,
                              RateLimiterService rateLimiterService,
                              RateLimitMetricsService rateLimitMetricsService,
                              LatencyMetricsService latencyMetricsService,
                              RequestLogService requestLogService) {
        this.routeService = routeService;
        this.tenantService = tenantService;
        this.rateLimiterService = rateLimiterService;
        this.rateLimitMetricsService = rateLimitMetricsService;
        this.latencyMetricsService = latencyMetricsService;
        this.requestLogService = requestLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isInternalPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long gatewayStartNanos = System.nanoTime();
        String requestId = requestId(request);
        request.setAttribute(GatewayRequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        wrappedResponse.setHeader(GatewayHeaders.REQUEST_ID, requestId);
        wrappedResponse.setHeader(GatewayHeaders.GATEWAY_INSTANCE_ID, rateLimitMetricsService.gatewayInstanceId());
        String rateLimitDecision = RATE_LIMIT_SKIPPED;

        Optional<GatewayRoute> route = routeService.match(request.getRequestURI());
        if (route.isEmpty()) {
            writeGatewayError(wrappedResponse, HttpStatus.NOT_FOUND, "route not found");
            finish(request, wrappedResponse, gatewayStartNanos, rateLimitDecision);
            return;
        }

        GatewayRoute matchedRoute = route.get();
        request.setAttribute(GatewayRequestContext.ROUTE_ATTRIBUTE, matchedRoute);
        if (!matchedRoute.allows(HttpMethod.valueOf(request.getMethod()))) {
            writeGatewayError(wrappedResponse, HttpStatus.METHOD_NOT_ALLOWED, "method not allowed for route");
            finish(request, wrappedResponse, gatewayStartNanos, rateLimitDecision);
            return;
        }

        Optional<Tenant> tenant = Optional.ofNullable(tenantService.authenticate(request.getHeader(GatewayHeaders.API_KEY)))
                .orElse(Optional.empty());
        if (tenant.isEmpty()) {
            writeGatewayError(wrappedResponse, HttpStatus.UNAUTHORIZED, "valid X-API-Key required");
            finish(request, wrappedResponse, gatewayStartNanos, rateLimitDecision);
            return;
        }

        request.setAttribute(GatewayRequestContext.TENANT_ATTRIBUTE, tenant.get());
        long rateLimiterStartNanos = System.nanoTime();
        RateLimitDecision decision = rateLimiterService.tryConsume(request);
        long rateLimiterNanos = System.nanoTime() - rateLimiterStartNanos;
        rateLimitMetricsService.record(matchedRoute.routeId(), decision, rateLimiterNanos);

        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_LIMIT, String.valueOf(decision.limit()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_REMAINING, String.valueOf(decision.remaining()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_RESET, String.valueOf(decision.resetSeconds()));
        wrappedResponse.setHeader(GatewayHeaders.RATE_LIMIT_LATENCY_MS, LatencyFormatter.millis(rateLimiterNanos));
        latencyMetricsService.record("rate_limiter", request.getRequestURI(), decision.allowed() ? 200 : 429, rateLimiterNanos);

        if (!decision.allowed()) {
            rateLimitDecision = RATE_LIMIT_REJECTED;
            int tooManyRequestsStatus = HttpStatus.TOO_MANY_REQUESTS.value();
            wrappedResponse.setStatus(tooManyRequestsStatus);
            wrappedResponse.setHeader(GatewayHeaders.RETRY_AFTER, String.valueOf(decision.resetSeconds()));
            wrappedResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrappedResponse.getWriter().write("{\"error\":\"Distributed rate limit exceeded. Retry after " + decision.resetSeconds() + " seconds.\"}");
            finish(request, wrappedResponse, gatewayStartNanos, rateLimitDecision);
            return;
        }

        try {
            rateLimitDecision = RATE_LIMIT_ALLOWED;
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            finish(request, wrappedResponse, gatewayStartNanos, rateLimitDecision);
        }
    }

    private boolean isInternalPath(String path) {
        return path.startsWith("/admin") || path.startsWith("/actuator") || path.startsWith("/internal");
    }

    private String requestId(HttpServletRequest request) {
        String existing = request.getHeader(GatewayHeaders.REQUEST_ID);
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    private void writeGatewayError(ContentCachingResponseWrapper response,
                                   HttpStatus status,
                                   String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private void finish(HttpServletRequest request,
                        ContentCachingResponseWrapper response,
                        long gatewayStartNanos,
                        String rateLimitDecision) throws IOException {
        long gatewayNanos = System.nanoTime() - gatewayStartNanos;
        response.setHeader(GatewayHeaders.GATEWAY_LATENCY_MS, LatencyFormatter.millis(gatewayNanos));
        latencyMetricsService.record("gateway_total", request.getRequestURI(), response.getStatus(), gatewayNanos);
        requestLogService.record(new RequestLogService.RequestLogEntry(
                Instant.now(),
                tenantId(request),
                routeId(request),
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                LatencyFormatter.roundedMillis(gatewayNanos),
                parseLatencyHeader(response.getHeader(GatewayHeaders.BACKEND_LATENCY_MS)),
                rateLimitDecision,
                ClientIdentity.from(request),
                GatewayRequestContext.requestId(request),
                rateLimitMetricsService.gatewayInstanceId()
        ));
        response.copyBodyToResponse();
    }

    private String tenantId(HttpServletRequest request) {
        Tenant tenant = GatewayRequestContext.tenant(request);
        return tenant == null ? null : tenant.id();
    }

    private String routeId(HttpServletRequest request) {
        GatewayRoute route = GatewayRequestContext.route(request);
        return route == null ? null : route.routeId();
    }

    private double parseLatencyHeader(String header) {
        if (header == null || header.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(header);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
