package com.example.apigateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthFilterTest {

    @Test
    void allowsAdminHealthWithoutToken() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void rejectsAdminRouteWithoutToken() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("admin-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/routes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void allowsAdminRouteWithToken() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("admin-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/routes");
        request.addHeader("X-Admin-Token", "admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void disablesAdminRouteWhenTokenIsMissingFromConfiguration() throws Exception {
        AdminAuthFilter filter = new AdminAuthFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/routes");
        request.addHeader("X-Admin-Token", "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
}
