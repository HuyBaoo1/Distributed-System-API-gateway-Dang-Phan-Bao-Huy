package com.example.apigateway.service;

import com.example.apigateway.config.RateLimiterProperties;
import com.example.apigateway.model.GatewayRoute;
import com.example.apigateway.model.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "rate.limit.strategy=redis-sliding-window",
        "rate.limit.requests-per-minute=2",
        "rate.limit.window-seconds=2"
})
@Testcontainers(disabledWithoutDocker = true)
class RedisSlidingWindowRateLimiterServiceTestcontainersTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    @Qualifier("slidingWindowRateLimitScript")
    private RedisScript<List> slidingWindowRateLimitScript;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimiterProperties properties;

    @Autowired
    private RedisFailureHandler redisFailureHandler;

    @Test
    void enforcesDistributedSlidingWindowLimitInRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        MockHttpServletRequest request = request("tenant-a", "mock-api");

        RateLimitDecision first = rateLimiterService.tryConsume(request);
        RateLimitDecision second = rateLimiterService.tryConsume(request);
        RateLimitDecision third = rateLimiterService.tryConsume(request);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(1);
        assertThat(second.allowed()).isTrue();
        assertThat(second.remaining()).isEqualTo(0);
        assertThat(third.allowed()).isFalse();
        assertThat(third.limit()).isEqualTo(2);
        assertThat(third.resetSeconds()).isBetween(0L, 2L);
    }

    @Test
    void twoLimiterInstancesShareOneRedisQuota() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        RedisSlidingWindowRateLimiterService replicaA = replica();
        RedisSlidingWindowRateLimiterService replicaB = replica();

        RateLimitDecision first = replicaA.tryConsume(request("tenant-a", "mock-api"));
        RateLimitDecision second = replicaB.tryConsume(request("tenant-a", "mock-api"));
        RateLimitDecision third = replicaA.tryConsume(request("tenant-a", "mock-api"));

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
    }

    @Test
    void concurrentRequestsDoNotOvershootConfiguredQuota() throws Exception {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        int totalRequests = 40;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                await(start);
                if (rateLimiterService.tryConsume(request("tenant-a", "mock-api")).allowed()) {
                    allowed.incrementAndGet();
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int quotaOvershoot = Math.max(0, allowed.get() - 2);
        assertThat(allowed.get()).isEqualTo(2);
        assertThat(quotaOvershoot).isZero();
    }

    @Test
    void isolatesQuotaByTenantAndRoute() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        assertThat(rateLimiterService.tryConsume(request("tenant-a", "route-a")).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume(request("tenant-a", "route-a")).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume(request("tenant-a", "route-a")).allowed()).isFalse();

        assertThat(rateLimiterService.tryConsume(request("tenant-b", "route-a")).allowed()).isTrue();
        assertThat(rateLimiterService.tryConsume(request("tenant-a", "route-b")).allowed()).isTrue();
    }

    private RedisSlidingWindowRateLimiterService replica() {
        return new RedisSlidingWindowRateLimiterService(
                redisTemplate,
                slidingWindowRateLimitScript,
                properties,
                redisFailureHandler);
    }

    private MockHttpServletRequest request(String tenantId, String routeId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.setRemoteAddr("198.51.100.10");
        request.setAttribute(GatewayRequestContext.TENANT_ATTRIBUTE,
                new Tenant(tenantId, tenantId, "hash", "free", true, Instant.now()));
        request.setAttribute(GatewayRequestContext.ROUTE_ATTRIBUTE,
                new GatewayRoute(routeId, "/api/v1/**", "http://backend:8081",
                        Set.of(HttpMethod.GET), true, 2, 2, Instant.now(), Instant.now()));
        return request;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
