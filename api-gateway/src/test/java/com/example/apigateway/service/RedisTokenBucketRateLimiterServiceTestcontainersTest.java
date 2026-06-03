package com.example.apigateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "rate.limit.strategy=redis-token-bucket",
        "rate.limit.requests-per-minute=2",
        "rate.limit.window-seconds=2"
})
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketRateLimiterServiceTestcontainersTest {

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
    private StringRedisTemplate redisTemplate;

    @Test
    void smoothsRequestsWithTokenBucketCapacityAndRefill() throws InterruptedException {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.setRemoteAddr("198.51.100.50");

        RateLimitDecision first = rateLimiterService.tryConsume(request);
        RateLimitDecision second = rateLimiterService.tryConsume(request);
        RateLimitDecision third = rateLimiterService.tryConsume(request);
        Thread.sleep(1100);
        RateLimitDecision afterRefill = rateLimiterService.tryConsume(request);

        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).isTrue();
        assertThat(third.allowed()).isFalse();
        assertThat(afterRefill.allowed()).isTrue();
    }
}
