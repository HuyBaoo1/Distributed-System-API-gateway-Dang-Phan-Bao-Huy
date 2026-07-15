package com.example.apigateway.controller;

import com.example.apigateway.config.BackendProperties;
import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.service.BackendCircuitBreaker;
import com.example.apigateway.service.GatewayRequestContext;
import com.example.apigateway.service.LatencyFormatter;
import com.example.apigateway.service.LatencyMetricsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;

@RestController
public class ApiGatewayController {

    private final RestTemplate restTemplate;
    private final BackendProperties backendProperties;
    private final LatencyMetricsService latencyMetricsService;
    private final BackendCircuitBreaker backendCircuitBreaker;

    public ApiGatewayController(RestTemplate restTemplate,
                                BackendProperties backendProperties,
                                LatencyMetricsService latencyMetricsService,
                                BackendCircuitBreaker backendCircuitBreaker) {
        this.restTemplate = restTemplate;
        this.backendProperties = backendProperties;
        this.latencyMetricsService = latencyMetricsService;
        this.backendCircuitBreaker = backendCircuitBreaker;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> forward(HttpServletRequest request,
                                          @RequestBody(required = false) String body) {
        if (!backendCircuitBreaker.allowRequest()) {
            latencyMetricsService.record("backend_proxy", request.getRequestURI(), HttpStatus.SERVICE_UNAVAILABLE.value(), 0);
            return gatewayError(HttpStatus.SERVICE_UNAVAILABLE,
                    "Backend circuit breaker is open",
                    0,
                    0);
        }

        String backendUrl = buildBackendUrl(request);
        HttpEntity<String> requestEntity = new HttpEntity<>(body, copyHeaders(request));
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        int maxAttempts = maxAttemptsFor(method);

        long backendStartNanos = System.nanoTime();
        ResponseEntity<String> backendResponse = null;
        RestClientException lastException = null;
        int attempts = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            try {
                backendResponse = restTemplate.exchange(backendUrl, method, requestEntity, String.class);
                lastException = null;
                if (shouldRetryBackendResponse(method, backendResponse, attempt, maxAttempts)) {
                    sleepBeforeRetry();
                    continue;
                }
                break;
            } catch (ResourceAccessException ex) {
                lastException = ex;
                if (attempt < maxAttempts) {
                    sleepBeforeRetry();
                    continue;
                }
                break;
            } catch (RestClientException ex) {
                lastException = ex;
                break;
            }
        }

        long backendNanos = System.nanoTime() - backendStartNanos;

        if (lastException != null || backendResponse == null) {
            HttpStatus status = lastException instanceof ResourceAccessException
                    ? HttpStatus.GATEWAY_TIMEOUT
                    : HttpStatus.BAD_GATEWAY;
            backendCircuitBreaker.recordFailure();
            latencyMetricsService.record("backend_proxy", request.getRequestURI(), status.value(), backendNanos);
            return gatewayError(status, "Backend request failed", backendNanos, attempts);
        }

        backendCircuitBreaker.recordResult(backendResponse.getStatusCode().value());
        latencyMetricsService.record("backend_proxy", request.getRequestURI(), backendResponse.getStatusCode().value(), backendNanos);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(backendResponse.getHeaders());
        removeHopSpecificResponseHeaders(responseHeaders);
        responseHeaders.set(GatewayHeaders.BACKEND_LATENCY_MS, LatencyFormatter.millis(backendNanos));
        responseHeaders.set(GatewayHeaders.BACKEND_ATTEMPTS, String.valueOf(attempts));
        responseHeaders.set(GatewayHeaders.CIRCUIT_BREAKER_STATE, backendCircuitBreaker.state());
        return ResponseEntity.status(backendResponse.getStatusCode())
                .headers(responseHeaders)
                .body(backendResponse.getBody());
    }

    private String buildBackendUrl(HttpServletRequest request) {
        GatewayRoute route = GatewayRequestContext.route(request);
        String targetUrl = route == null ? backendProperties.baseUrl() : route.targetUrl();
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(targetUrl)
                .path(request.getRequestURI());
        if (request.getQueryString() != null) {
            builder.query(request.getQueryString());
        }
        return builder.build(true).toUriString();
    }

    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (isHopSpecificHeader(headerName)) {
                    continue;
                }
                if (headerName.equalsIgnoreCase(GatewayHeaders.API_KEY)
                        || headerName.equalsIgnoreCase("X-Admin-Token")) {
                    continue;
                }
                headers.put(headerName, Collections.list(request.getHeaders(headerName)));
            }
        }
        return headers;
    }

    private boolean isHopSpecificHeader(String headerName) {
        return headerName.equalsIgnoreCase(HttpHeaders.HOST)
                || headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                || headerName.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING)
                || headerName.equalsIgnoreCase(HttpHeaders.CONNECTION)
                || headerName.equalsIgnoreCase(HttpHeaders.UPGRADE)
                || headerName.equalsIgnoreCase("Keep-Alive")
                || headerName.equalsIgnoreCase("Proxy-Authenticate")
                || headerName.equalsIgnoreCase("Proxy-Authorization")
                || headerName.equalsIgnoreCase("TE")
                || headerName.equalsIgnoreCase("Trailer");
    }

    private void removeHopSpecificResponseHeaders(HttpHeaders headers) {
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.remove(HttpHeaders.CONNECTION);
        headers.remove(HttpHeaders.UPGRADE);
        headers.remove("Keep-Alive");
        headers.remove("Proxy-Authenticate");
        headers.remove("Proxy-Authorization");
        headers.remove("TE");
        headers.remove("Trailer");
    }

    private int maxAttemptsFor(HttpMethod method) {
        if (!isIdempotentMethod(method)) {
            return 1;
        }
        return Math.max(1, backendProperties.maxAttempts());
    }

    private boolean shouldRetryBackendResponse(HttpMethod method,
                                               ResponseEntity<String> backendResponse,
                                               int attempt,
                                               int maxAttempts) {
        return isIdempotentMethod(method)
                && attempt < maxAttempts
                && backendResponse.getStatusCode().is5xxServerError();
    }

    private boolean isIdempotentMethod(HttpMethod method) {
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method);
    }

    private void sleepBeforeRetry() {
        int backoffMs = Math.max(0, backendProperties.retryBackoffMs());
        if (backoffMs == 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private ResponseEntity<String> gatewayError(HttpStatus status,
                                                String message,
                                                long backendNanos,
                                                int attempts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(GatewayHeaders.BACKEND_LATENCY_MS, LatencyFormatter.millis(backendNanos));
        headers.set(GatewayHeaders.BACKEND_ATTEMPTS, String.valueOf(attempts));
        headers.set(GatewayHeaders.CIRCUIT_BREAKER_STATE, backendCircuitBreaker.state());
        String body = "{\"error\":\"" + message + "\",\"status\":" + status.value() + "}";
        return ResponseEntity.status(status)
                .headers(headers)
                .body(body);
    }
}
