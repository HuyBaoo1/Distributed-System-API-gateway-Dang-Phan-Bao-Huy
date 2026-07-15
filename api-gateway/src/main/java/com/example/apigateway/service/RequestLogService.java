package com.example.apigateway.service;

import com.example.apigateway.repository.RequestLogRepository;
import com.example.apigateway.repository.RequestLogRepository.RequestLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
public class RequestLogService {

    private static final Logger LOGGER = LoggerFactory.getLogger("gateshield.requests");

    private final RequestLogRepository requestLogRepository;

    public RequestLogService(RequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
    }

    public void record(RequestLogEntry entry) {
        LOGGER.info("{}", entry.toJson());
        try {
            requestLogRepository.save(entry);
        } catch (RuntimeException ex) {
            LOGGER.warn("request log persistence failed: {}", ex.getMessage());
        }
    }

    public Map<String, Object> summary() {
        return requestLogRepository.summary();
    }

    public RequestLogPage recent(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        return new RequestLogPage(
                safePage,
                safeSize,
                requestLogRepository.count(),
                requestLogRepository.findRecent(safeSize, safePage * safeSize)
        );
    }

    public record RequestLogEntry(
            Instant timestamp,
            String tenantId,
            String routeId,
            String method,
            String path,
            int statusCode,
            double gatewayLatencyMs,
            double backendLatencyMs,
            String rateLimitDecision,
            String clientIp,
            String requestId,
            String gatewayInstanceId
    ) {
        private String toJson() {
            return "{"
                    + "\"timestamp\":\"" + escape(timestamp.toString()) + "\","
                    + "\"tenantId\":\"" + escape(tenantId) + "\","
                    + "\"routeId\":\"" + escape(routeId) + "\","
                    + "\"method\":\"" + escape(method) + "\","
                    + "\"path\":\"" + escape(path) + "\","
                    + "\"statusCode\":" + statusCode + ","
                    + "\"gatewayLatencyMs\":" + gatewayLatencyMs + ","
                    + "\"backendLatencyMs\":" + backendLatencyMs + ","
                    + "\"rateLimitDecision\":\"" + escape(rateLimitDecision) + "\","
                    + "\"clientIp\":\"" + escape(clientIp) + "\","
                    + "\"requestId\":\"" + escape(requestId) + "\","
                    + "\"gatewayInstanceId\":\"" + escape(gatewayInstanceId) + "\""
                    + "}";
        }

        private String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public record RequestLogPage(int page, int size, long total, java.util.List<RequestLogRow> items) {
    }
}
