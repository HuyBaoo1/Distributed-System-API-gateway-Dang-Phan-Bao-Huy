package com.example.apigateway.controller;

import com.example.apigateway.config.BackendProperties;
import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.service.BackendCircuitBreaker;
import com.example.apigateway.service.LatencyMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApiGatewayControllerTest {

    @Test
    void returnsServiceUnavailableWhenCircuitBreakerIsOpen() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        BackendProperties properties = backendProperties();
        properties.setCircuitBreakerFailureThreshold(1);
        BackendCircuitBreaker circuitBreaker = new BackendCircuitBreaker(properties);
        circuitBreaker.recordFailure();
        ApiGatewayController controller = controller(restTemplate, properties, circuitBreaker);

        ResponseEntity<String> response = controller.forward(request("GET"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(GatewayHeaders.CIRCUIT_BREAKER_STATE)).isEqualTo("open");
        assertThat(response.getHeaders().getFirst(GatewayHeaders.BACKEND_ATTEMPTS)).isEqualTo("0");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void retriesIdempotentBackendTimeouts() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        BackendProperties properties = backendProperties();
        properties.setMaxAttempts(2);
        properties.setRetryBackoffMs(0);
        BackendCircuitBreaker circuitBreaker = new BackendCircuitBreaker(properties);
        ApiGatewayController controller = controller(restTemplate, properties, circuitBreaker);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>any(),
                eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"))
                .thenReturn(ResponseEntity.ok("ok"));

        ResponseEntity<String> response = controller.forward(request("GET"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("ok");
        assertThat(response.getHeaders().getFirst(GatewayHeaders.BACKEND_ATTEMPTS)).isEqualTo("2");
        assertThat(response.getHeaders().getFirst(GatewayHeaders.CIRCUIT_BREAKER_STATE)).isEqualTo("closed");
        verify(restTemplate, times(2)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.<HttpEntity<String>>any(),
                eq(String.class));
    }

    private ApiGatewayController controller(RestTemplate restTemplate,
                                            BackendProperties properties,
                                            BackendCircuitBreaker circuitBreaker) {
        return new ApiGatewayController(
                restTemplate,
                properties,
                new LatencyMetricsService(),
                circuitBreaker);
    }

    private BackendProperties backendProperties() {
        BackendProperties properties = new BackendProperties();
        properties.setBaseUrl("http://backend:8081");
        return properties;
    }

    private MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/v1/hello");
    }
}
