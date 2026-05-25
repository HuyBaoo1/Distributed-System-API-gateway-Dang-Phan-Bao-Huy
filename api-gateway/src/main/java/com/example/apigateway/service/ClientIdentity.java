package com.example.apigateway.service;

import com.example.apigateway.http.GatewayHeaders;
import jakarta.servlet.http.HttpServletRequest;

public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String from(HttpServletRequest request) {
        String forwardedFor = request.getHeader(GatewayHeaders.X_FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return request.getRemoteAddr();
        }

        int firstSeparator = forwardedFor.indexOf(',');
        if (firstSeparator < 0) {
            return forwardedFor.trim();
        }
        return forwardedFor.substring(0, firstSeparator).trim();
    }
}
