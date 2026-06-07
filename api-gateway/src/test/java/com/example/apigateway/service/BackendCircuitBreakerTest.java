package com.example.apigateway.service;

import com.example.apigateway.config.BackendProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackendCircuitBreakerTest {

    @Test
    void opensAfterConfiguredFailureThreshold() {
        BackendProperties properties = new BackendProperties();
        properties.setCircuitBreakerFailureThreshold(2);
        BackendCircuitBreaker circuitBreaker = new BackendCircuitBreaker(properties);

        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.state()).isEqualTo("closed");

        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.allowRequest()).isFalse();
        assertThat(circuitBreaker.state()).isEqualTo("open");
    }

    @Test
    void allowsRequestAfterResetTimeout() throws Exception {
        BackendProperties properties = new BackendProperties();
        properties.setCircuitBreakerFailureThreshold(1);
        properties.setCircuitBreakerResetTimeoutMs(10);
        BackendCircuitBreaker circuitBreaker = new BackendCircuitBreaker(properties);

        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.allowRequest()).isFalse();

        Thread.sleep(20);

        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.state()).isEqualTo("closed");
    }

    @Test
    void disabledCircuitBreakerNeverBlocksRequests() {
        BackendProperties properties = new BackendProperties();
        properties.setCircuitBreakerEnabled(false);
        BackendCircuitBreaker circuitBreaker = new BackendCircuitBreaker(properties);

        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertThat(circuitBreaker.allowRequest()).isTrue();
        assertThat(circuitBreaker.state()).isEqualTo("disabled");
    }
}
