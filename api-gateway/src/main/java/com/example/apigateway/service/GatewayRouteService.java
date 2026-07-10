package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.repository.GatewayRouteRepository;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class GatewayRouteService {

    private final GatewayRouteRepository routeRepository;
    private final RateLimiterProperties rateLimiterProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public GatewayRouteService(GatewayRouteRepository routeRepository,
                               RateLimiterProperties rateLimiterProperties) {
        this.routeRepository = routeRepository;
        this.rateLimiterProperties = rateLimiterProperties;
    }

    public List<GatewayRoute> findAll() {
        return routeRepository.findAll();
    }

    public Optional<GatewayRoute> match(String path) {
        return routeRepository.findAll().stream()
                .filter(GatewayRoute::enabled)
                .filter(route -> pathMatcher.match(route.pathPattern(), path))
                .sorted((left, right) -> pathMatcher.getPatternComparator(path)
                        .compare(left.pathPattern(), right.pathPattern()))
                .findFirst();
    }

    public GatewayRoute create(String routeId,
                               String pathPattern,
                               String targetUrl,
                               Set<String> allowedMethods,
                               Boolean enabled,
                               Integer rateLimitRequests,
                               Integer rateLimitWindowSeconds) {
        Instant now = Instant.now();
        GatewayRoute route = new GatewayRoute(
                required(routeId, "routeId"),
                required(pathPattern, "pathPattern"),
                required(targetUrl, "targetUrl"),
                parseMethods(allowedMethods),
                enabled == null || enabled,
                positiveOrDefault(rateLimitRequests, rateLimiterProperties.requestsPerMinute()),
                positiveOrDefault(rateLimitWindowSeconds, rateLimiterProperties.windowSeconds()),
                now,
                now);
        return routeRepository.save(route);
    }

    public GatewayRoute update(String routeId,
                               String pathPattern,
                               String targetUrl,
                               Set<String> allowedMethods,
                               Boolean enabled,
                               Integer rateLimitRequests,
                               Integer rateLimitWindowSeconds) {
        GatewayRoute existing = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("route not found: " + routeId));
        GatewayRoute updated = new GatewayRoute(
                existing.routeId(),
                defaultValue(pathPattern, existing.pathPattern()),
                defaultValue(targetUrl, existing.targetUrl()),
                allowedMethods == null || allowedMethods.isEmpty() ? existing.allowedMethods() : parseMethods(allowedMethods),
                enabled == null ? existing.enabled() : enabled,
                positiveOrDefault(rateLimitRequests, existing.rateLimitRequests()),
                positiveOrDefault(rateLimitWindowSeconds, existing.rateLimitWindowSeconds()),
                existing.createdAt(),
                Instant.now());
        return routeRepository.update(updated);
    }

    private Set<HttpMethod> parseMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of(HttpMethod.GET);
        }
        LinkedHashSet<HttpMethod> parsed = new LinkedHashSet<>();
        for (String method : methods) {
            if (method != null && !method.isBlank()) {
                parsed.add(HttpMethod.valueOf(method.trim().toUpperCase()));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("allowedMethods must contain at least one HTTP method");
        }
        return parsed;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("rate limit values must be positive");
        }
        return value;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
