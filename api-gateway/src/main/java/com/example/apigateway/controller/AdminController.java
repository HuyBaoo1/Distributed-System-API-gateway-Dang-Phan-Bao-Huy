package com.example.apigateway.controller;

import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.model.Tenant;
import com.example.apigateway.service.GatewayRouteService;
import com.example.apigateway.service.LatencyMetricsService;
import com.example.apigateway.service.RequestLogService;
import com.example.apigateway.service.TenantService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final GatewayRouteService routeService;
    private final TenantService tenantService;
    private final RequestLogService requestLogService;
    private final LatencyMetricsService latencyMetricsService;

    public AdminController(GatewayRouteService routeService,
                           TenantService tenantService,
                           RequestLogService requestLogService,
                           LatencyMetricsService latencyMetricsService) {
        this.routeService = routeService;
        this.tenantService = tenantService;
        this.requestLogService = requestLogService;
        this.latencyMetricsService = latencyMetricsService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", "GateShield",
                "timestamp", Instant.now().toString());
    }

    @GetMapping("/routes")
    public List<RouteResponse> routes() {
        return routeService.findAll().stream()
                .map(RouteResponse::from)
                .toList();
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse createRoute(@RequestBody RouteRequest request) {
        try {
            return RouteResponse.from(routeService.create(
                    request.routeId(),
                    request.pathPattern(),
                    request.targetUrl(),
                    request.allowedMethods(),
                    request.enabled(),
                    request.rateLimitRequests(),
                    request.rateLimitWindowSeconds()));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PutMapping("/routes/{routeId}")
    public RouteResponse updateRoute(@PathVariable String routeId,
                                     @RequestBody RouteRequest request) {
        try {
            return RouteResponse.from(routeService.update(
                    routeId,
                    request.pathPattern(),
                    request.targetUrl(),
                    request.allowedMethods(),
                    request.enabled(),
                    request.rateLimitRequests(),
                    request.rateLimitWindowSeconds()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/tenants")
    public List<TenantResponse> tenants() {
        return tenantService.findAll().stream()
                .map(TenantResponse::from)
                .toList();
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@RequestBody TenantRequest request) {
        try {
            TenantService.TenantCreation creation = tenantService.create(
                    request.id(),
                    request.name(),
                    request.planName(),
                    request.enabled(),
                    request.apiKey());
            return TenantResponse.from(creation.tenant(), creation.apiKey());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PutMapping("/tenants/{tenantId}")
    public TenantResponse updateTenant(@PathVariable String tenantId,
                                       @RequestBody TenantRequest request) {
        try {
            TenantService.TenantCreation creation = tenantService.update(
                    tenantId,
                    request.name(),
                    request.planName(),
                    request.enabled(),
                    request.apiKey());
            return TenantResponse.from(creation.tenant(), creation.apiKey());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/usage/summary")
    public Map<String, Object> usageSummary() {
        return requestLogService.summary();
    }

    @GetMapping("/request-logs")
    public RequestLogService.RequestLogPage requestLogs(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return requestLogService.recent(page == null ? 0 : page, size == null ? 25 : size);
    }

    @GetMapping("/system/status")
    public Map<String, Object> systemStatus() {
        return Map.of(
                "generatedAt", Instant.now().toString(),
                "health", health(),
                "tenants", tenantService.findAll().size(),
                "routes", routeService.findAll().size(),
                "usage", requestLogService.summary(),
                "latency", latencyMetricsService.snapshot()
        );
    }

    public record RouteRequest(
            String routeId,
            String pathPattern,
            String targetUrl,
            Set<String> allowedMethods,
            Boolean enabled,
            Integer rateLimitRequests,
            Integer rateLimitWindowSeconds
    ) {
    }

    public record TenantRequest(
            String id,
            String name,
            String apiKey,
            String planName,
            Boolean enabled
    ) {
    }

    public record RouteResponse(
            String routeId,
            String pathPattern,
            String targetUrl,
            Set<String> allowedMethods,
            boolean enabled,
            int rateLimitRequests,
            int rateLimitWindowSeconds,
            Instant createdAt,
            Instant updatedAt
    ) {
        private static RouteResponse from(GatewayRoute route) {
            return new RouteResponse(
                    route.routeId(),
                    route.pathPattern(),
                    route.targetUrl(),
                    route.allowedMethods().stream()
                            .map(HttpMethod::name)
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    route.enabled(),
                    route.rateLimitRequests(),
                    route.rateLimitWindowSeconds(),
                    route.createdAt(),
                    route.updatedAt());
        }
    }

    public record TenantResponse(
            String id,
            String name,
            String planName,
            boolean enabled,
            Instant createdAt,
            String apiKey
    ) {
        private static TenantResponse from(Tenant tenant) {
            return from(tenant, null);
        }

        private static TenantResponse from(Tenant tenant, String apiKey) {
            return new TenantResponse(
                    tenant.id(),
                    tenant.name(),
                    tenant.planName(),
                    tenant.enabled(),
                    tenant.createdAt(),
                    apiKey);
        }
    }
}
