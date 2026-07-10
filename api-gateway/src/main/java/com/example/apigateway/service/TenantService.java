package com.example.apigateway.service;

import com.example.apigateway.model.Tenant;
import com.example.apigateway.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final ApiKeyHasher apiKeyHasher;

    public TenantService(TenantRepository tenantRepository, ApiKeyHasher apiKeyHasher) {
        this.tenantRepository = tenantRepository;
        this.apiKeyHasher = apiKeyHasher;
    }

    public List<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    public Optional<Tenant> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        String hash = apiKeyHasher.hash(apiKey);
        return tenantRepository.findByApiKeyHash(hash)
                .filter(Tenant::enabled);
    }

    public TenantCreation create(String id,
                                 String name,
                                 String planName,
                                 Boolean enabled,
                                 String apiKey) {
        String safeId = required(id, "tenant id");
        String safeName = required(name, "tenant name");
        String plainApiKey = apiKey == null || apiKey.isBlank() ? apiKeyHasher.generateApiKey() : apiKey;
        Tenant tenant = new Tenant(
                safeId,
                safeName,
                apiKeyHasher.hash(plainApiKey),
                defaultValue(planName, "free"),
                enabled == null || enabled,
                Instant.now());
        return new TenantCreation(tenantRepository.save(tenant), plainApiKey);
    }

    public TenantCreation update(String id,
                                 String name,
                                 String planName,
                                 Boolean enabled,
                                 String apiKey) {
        Tenant existing = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found: " + id));
        String plainApiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        Tenant updated = new Tenant(
                existing.id(),
                defaultValue(name, existing.name()),
                plainApiKey == null ? existing.apiKeyHash() : apiKeyHasher.hash(plainApiKey),
                defaultValue(planName, existing.planName()),
                enabled == null ? existing.enabled() : enabled,
                existing.createdAt());
        return new TenantCreation(tenantRepository.update(updated), plainApiKey);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record TenantCreation(Tenant tenant, String apiKey) {
    }
}
