package com.example.apigateway.service;

import com.example.apigateway.http.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIdentityTest {

    @Test
    void usesFirstForwardedIpWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader(GatewayHeaders.X_FORWARDED_FOR, "203.0.113.1, 10.0.0.1");

        assertThat(ClientIdentity.from(request)).isEqualTo("203.0.113.1");
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");

        assertThat(ClientIdentity.from(request)).isEqualTo("10.0.0.10");
    }
}
