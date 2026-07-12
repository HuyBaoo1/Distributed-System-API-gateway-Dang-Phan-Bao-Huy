package com.example.apigateway.repository;

import com.example.apigateway.service.RequestLogService.RequestLogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    public long count() {
        return queryLong("select count(*) from request_logs");
    }

    public List<RequestLogRow> findRecent(int limit, int offset) {
        return jdbcTemplate.query("""
                select timestamp, tenant_id, route_id, method, path, status_code,
                       gateway_latency_ms, backend_latency_ms, rate_limit_decision, client_ip, request_id
                from request_logs
                order by timestamp desc
                limit ? offset ?
                """, this::mapRow, limit, offset);
    }

    private RequestLogRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp timestamp = rs.getTimestamp("timestamp");
        return new RequestLogRow(
                timestamp == null ? Instant.EPOCH : timestamp.toInstant(),
                rs.getString("tenant_id"),
                rs.getString("route_id"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getInt("status_code"),
                rs.getDouble("gateway_latency_ms"),
                rs.getDouble("backend_latency_ms"),
                rs.getString("rate_limit_decision"),
                rs.getString("client_ip"),
                rs.getString("request_id")
        );
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private double queryDouble(String sql) {
        Double value = jdbcTemplate.queryForObject(sql, Double.class);
        return value == null ? 0.0 : value;
    }

    public record RequestLogRow(
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
            String requestId
    ) {
    }
}
