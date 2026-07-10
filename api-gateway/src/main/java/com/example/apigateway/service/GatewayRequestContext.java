package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.model.Tenant;
import jakarta.servlet.http.HttpServletRequest;

public final class GatewayRequestContext {

    public static final String TENANT_ATTRIBUTE = GatewayRequestContext.class.getName() + ".tenant";
    public static final String ROUTE_ATTRIBUTE = GatewayRequestContext.class.getName() + ".route";
    public static final String REQUEST_ID_ATTRIBUTE = GatewayRequestContext.class.getName() + ".requestId";

    private GatewayRequestContext() {
    }

    public static Tenant tenant(HttpServletRequest request) {
        Object value = request.getAttribute(TENANT_ATTRIBUTE);
        return value instanceof Tenant tenant ? tenant : null;
    }

    public static GatewayRoute route(HttpServletRequest request) {
        Object value = request.getAttribute(ROUTE_ATTRIBUTE);
        return value instanceof GatewayRoute route ? route : null;
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value instanceof String requestId ? requestId : null;
    }

    public static String rateLimitKey(HttpServletRequest request) {
        Tenant tenant = tenant(request);
        GatewayRoute route = route(request);
        if (tenant == null || route == null) {
            String clientIp = ClientIdentity.from(request);
            return String.format("ratelimit:anonymous:%s:%s", clientIp, request.getRequestURI());
        }
        return String.format("ratelimit:%s:%s", tenant.id(), route.routeId());
    }

    public static int rateLimitRequests(HttpServletRequest request, RateLimiterProperties properties) {
        GatewayRoute route = route(request);
        return route == null ? properties.requestsPerMinute() : route.rateLimitRequests();
    }

    public static int rateLimitWindowSeconds(HttpServletRequest request, RateLimiterProperties properties) {
        GatewayRoute route = route(request);
        return route == null ? properties.windowSeconds() : route.rateLimitWindowSeconds();
    }
}
