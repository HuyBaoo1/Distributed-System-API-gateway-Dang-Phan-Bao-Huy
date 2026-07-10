package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.repository.GatewayRouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRouteServiceTest {

    @Test
    void matchesMostSpecificEnabledRoute() {
        GatewayRouteRepository repository = mock(GatewayRouteRepository.class);
        GatewayRoute general = route("api", "/api/**");
        GatewayRoute specific = route("hello", "/api/v1/hello");
        GatewayRoute disabled = new GatewayRoute(
                "disabled",
                "/api/v1/hello",
                "http://disabled",
                Set.of(HttpMethod.GET),
                false,
                1,
                60,
                Instant.now(),
                Instant.now());
        when(repository.findAll()).thenReturn(List.of(general, disabled, specific));

        GatewayRouteService service = new GatewayRouteService(repository, new RateLimiterProperties());

        assertThat(service.match("/api/v1/hello"))
                .hasValueSatisfying(route -> assertThat(route.routeId()).isEqualTo("hello"));
    }

    private GatewayRoute route(String routeId, String pathPattern) {
        return new GatewayRoute(
                routeId,
                pathPattern,
                "http://backend:8081",
                Set.of(HttpMethod.GET),
                true,
                10,
                60,
                Instant.now(),
                Instant.now());
    }
}
