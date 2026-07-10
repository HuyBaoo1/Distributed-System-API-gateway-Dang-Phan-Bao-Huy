package com.example.apigateway.repository;

import com.example.apigateway.model.GatewayRoute;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class GatewayRouteRepository {

    private final JdbcTemplate jdbcTemplate;

    public GatewayRouteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GatewayRoute> findAll() {
        return jdbcTemplate.query("""
                select route_id, path_pattern, target_url, allowed_methods, enabled,
                       rate_limit_requests, rate_limit_window_seconds, created_at, updated_at
                from gateway_routes
                order by route_id
                """, this::mapRoute);
    }

    public Optional<GatewayRoute> findById(String routeId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select route_id, path_pattern, target_url, allowed_methods, enabled,
                           rate_limit_requests, rate_limit_window_seconds, created_at, updated_at
                    from gateway_routes
                    where route_id = ?
                    """, this::mapRoute, routeId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public GatewayRoute save(GatewayRoute route) {
        jdbcTemplate.update("""
                insert into gateway_routes
                (route_id, path_pattern, target_url, allowed_methods, enabled,
                 rate_limit_requests, rate_limit_window_seconds, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                route.routeId(),
                route.pathPattern(),
                route.targetUrl(),
                joinMethods(route.allowedMethods()),
                route.enabled(),
                route.rateLimitRequests(),
                route.rateLimitWindowSeconds(),
                Timestamp.from(route.createdAt()),
                Timestamp.from(route.updatedAt()));
        return route;
    }

    public GatewayRoute update(GatewayRoute route) {
        jdbcTemplate.update("""
                update gateway_routes
                set path_pattern = ?, target_url = ?, allowed_methods = ?, enabled = ?,
                    rate_limit_requests = ?, rate_limit_window_seconds = ?, updated_at = ?
                where route_id = ?
                """,
                route.pathPattern(),
                route.targetUrl(),
                joinMethods(route.allowedMethods()),
                route.enabled(),
                route.rateLimitRequests(),
                route.rateLimitWindowSeconds(),
                Timestamp.from(route.updatedAt()),
                route.routeId());
        return route;
    }

    private GatewayRoute mapRoute(ResultSet rs, int rowNum) throws SQLException {
        return new GatewayRoute(
                rs.getString("route_id"),
                rs.getString("path_pattern"),
                rs.getString("target_url"),
                parseMethods(rs.getString("allowed_methods")),
                rs.getBoolean("enabled"),
                rs.getInt("rate_limit_requests"),
                rs.getInt("rate_limit_window_seconds"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private Set<HttpMethod> parseMethods(String methods) {
        if (methods == null || methods.isBlank()) {
            return Set.of(HttpMethod.GET);
        }
        return Arrays.stream(methods.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(HttpMethod::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String joinMethods(Set<HttpMethod> methods) {
        return methods.stream()
                .map(HttpMethod::name)
                .collect(Collectors.joining(","));
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
