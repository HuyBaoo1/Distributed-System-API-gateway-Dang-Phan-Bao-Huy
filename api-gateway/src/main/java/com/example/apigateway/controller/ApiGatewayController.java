package com.example.apigateway.controller;

import com.example.apigateway.http.GatewayHeaders;
import com.example.apigateway.service.LatencyFormatter;
import com.example.apigateway.service.LatencyMetricsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;

@RestController
public class ApiGatewayController {

    private final RestTemplate restTemplate;
    private final String backendBaseUrl;
    private final LatencyMetricsService latencyMetricsService;

    public ApiGatewayController(RestTemplate restTemplate,
                                @Value("${backend.base-url:http://localhost:8081}") String backendBaseUrl,
                                LatencyMetricsService latencyMetricsService) {
        this.restTemplate = restTemplate;
        this.backendBaseUrl = backendBaseUrl;
        this.latencyMetricsService = latencyMetricsService;
    }

    @RequestMapping("/api/v1/**")
    public ResponseEntity<String> forward(HttpServletRequest request,
                                          @RequestBody(required = false) String body) {
        String backendUrl = buildBackendUrl(request);
        HttpEntity<String> requestEntity = new HttpEntity<>(body, copyHeaders(request));
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        long backendStartNanos = System.nanoTime();
        ResponseEntity<String> backendResponse;
        long backendNanos;
        try {
            backendResponse = restTemplate.exchange(backendUrl, method, requestEntity, String.class);
            backendNanos = System.nanoTime() - backendStartNanos;
            latencyMetricsService.record("backend_proxy", request.getRequestURI(), backendResponse.getStatusCode().value(), backendNanos);
        } catch (RuntimeException ex) {
            backendNanos = System.nanoTime() - backendStartNanos;
            latencyMetricsService.record("backend_proxy", request.getRequestURI(), 502, backendNanos);
            throw ex;
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(backendResponse.getHeaders());
        responseHeaders.set(GatewayHeaders.BACKEND_LATENCY_MS, LatencyFormatter.millis(backendNanos));
        return ResponseEntity.status(backendResponse.getStatusCode())
                .headers(responseHeaders)
                .body(backendResponse.getBody());
    }

    private String buildBackendUrl(HttpServletRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(backendBaseUrl)
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
                headers.put(headerName, Collections.list(request.getHeaders(headerName)));
            }
        }
        return headers;
    }

    private boolean isHopSpecificHeader(String headerName) {
        return headerName.equalsIgnoreCase(HttpHeaders.HOST)
                || headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)
                || headerName.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING);
    }
}
