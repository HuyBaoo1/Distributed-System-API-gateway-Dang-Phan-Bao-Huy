package com.example.apigateway.repository;

import com.example.apigateway.model.Tenant;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TenantRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Tenant> findAll() {
        return jdbcTemplate.query("""
                select id, name, api_key_hash, plan_name, enabled, created_at
                from tenants
                order by created_at desc
                """, this::mapTenant);
    }

    public Optional<Tenant> findById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select id, name, api_key_hash, plan_name, enabled, created_at
                    from tenants
                    where id = ?
                    """, this::mapTenant, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<Tenant> findByApiKeyHash(String apiKeyHash) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select id, name, api_key_hash, plan_name, enabled, created_at
                    from tenants
                    where api_key_hash = ?
                    """, this::mapTenant, apiKeyHash));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Tenant save(Tenant tenant) {
        jdbcTemplate.update("""
                insert into tenants (id, name, api_key_hash, plan_name, enabled, created_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                tenant.id(),
                tenant.name(),
                tenant.apiKeyHash(),
                tenant.planName(),
                tenant.enabled(),
                Timestamp.from(tenant.createdAt()));
        return tenant;
    }

    public Tenant update(Tenant tenant) {
        jdbcTemplate.update("""
                update tenants
                set name = ?, api_key_hash = ?, plan_name = ?, enabled = ?
                where id = ?
                """,
                tenant.name(),
                tenant.apiKeyHash(),
                tenant.planName(),
                tenant.enabled(),
                tenant.id());
        return tenant;
    }

    private Tenant mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("api_key_hash"),
                rs.getString("plan_name"),
                rs.getBoolean("enabled"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
