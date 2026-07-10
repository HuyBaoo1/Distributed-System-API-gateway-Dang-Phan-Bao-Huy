package com.example.apigateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAuthFilter extends OncePerRequestFilter {

    private final String adminToken;

    public AdminAuthFilter(@Value("${gateshield.admin-token:}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/admin") || "/admin/health".equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (adminToken == null || adminToken.isBlank()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"GATESHIELD_ADMIN_TOKEN is not configured\"}");
            return;
        }

        if (!matchesAdminToken(request)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"admin token required\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesAdminToken(HttpServletRequest request) {
        String headerToken = request.getHeader("X-Admin-Token");
        if (adminToken.equals(headerToken)) {
            return true;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String bearerPrefix = "Bearer ";
        return authorization != null
                && authorization.startsWith(bearerPrefix)
                && adminToken.equals(authorization.substring(bearerPrefix.length()));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }
}
