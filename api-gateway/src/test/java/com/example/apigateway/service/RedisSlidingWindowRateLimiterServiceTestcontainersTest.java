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
    private StringRedisTemplate redisTemplate;

    @Test
    void enforcesDistributedSlidingWindowLimitInRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/hello");
        request.setRemoteAddr("198.51.100.10");

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
}
