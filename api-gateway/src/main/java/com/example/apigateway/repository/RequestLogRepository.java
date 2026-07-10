package com.example.apigateway.repository;

import com.example.apigateway.service.RequestLogService.RequestLogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class RequestLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public RequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(RequestLogEntry entry) {
        jdbcTemplate.update("""
                insert into request_logs
                (timestamp, tenant_id, route_id, method, path, status_code,
                 gateway_latency_ms, backend_latency_ms, rate_limit_decision, client_ip, request_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Timestamp.from(entry.timestamp()),
                entry.tenantId(),
                entry.routeId(),
                entry.method(),
                entry.path(),
                entry.statusCode(),
                entry.gatewayLatencyMs(),
                entry.backendLatencyMs(),
                entry.rateLimitDecision(),
                entry.clientIp(),
                entry.requestId());
    }

    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRequests", queryLong("select count(*) from request_logs"));
        summary.put("allowedRequests", queryLong("select count(*) from request_logs where rate_limit_decision = 'allowed'"));
        summary.put("rateLimitedRequests", queryLong("select count(*) from request_logs where status_code = 429"));
        summary.put("unauthorizedRequests", queryLong("select count(*) from request_logs where status_code = 401"));
        summary.put("avgGatewayLatencyMs", queryDouble("select coalesce(avg(gateway_latency_ms), 0) from request_logs"));
        summary.put("avgBackendLatencyMs", queryDouble("select coalesce(avg(backend_latency_ms), 0) from request_logs"));
        return summary;
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private double queryDouble(String sql) {
        Double value = jdbcTemplate.queryForObject(sql, Double.class);
        return value == null ? 0.0 : value;
    }
}
