package com.ws.wsAgenticSecurityGateway.wsServer.transport;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Authentication filter for the MCP HTTP endpoint ({@code /mcp/*}).
 *
 * <p>Validates incoming agent requests before they reach the
 * {@code HttpServletStreamableServerTransportProvider} servlet.
 * Currently supports a simple Bearer API key from configuration.
 *
 * <p><strong>Future:</strong> Can be enhanced to support JWT validation
 * (Azure AD), SHA-256 hashed DB-stored API keys, and per-agent rate limiting.
 */
@Slf4j
public class GatewayMcpAuthFilter implements Filter {

    private final String expectedApiKey;

    public GatewayMcpAuthFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || authHeader.isBlank()) {
            log.warn("🚫 MCP auth failed: missing Authorization header from {}",
                    request.getRemoteAddr());
            sendUnauthorized(response, "Missing Authorization header");
            return;
        }

        if (!isValidAuth(authHeader)) {
            log.warn("🚫 MCP auth failed: invalid credentials from {}",
                    request.getRemoteAddr());
            sendUnauthorized(response, "Invalid credentials");
            return;
        }

        log.debug("✅ MCP auth passed for {} from {}", maskAuth(authHeader), request.getRemoteAddr());
        chain.doFilter(servletRequest, servletResponse);
    }

    /**
     * Validates the Authorization header.
     * Supports: {@code Bearer <api-key>}
     */
    private boolean isValidAuth(String authHeader) {
        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            return token.equals(expectedApiKey);
        }
        // API key passed directly (without Bearer prefix)
        return authHeader.trim().equals(expectedApiKey);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"" + message + "\"}}");
    }

    private String maskAuth(String auth) {
        if (auth == null || auth.length() <= 12) return "***";
        return auth.substring(0, 10) + "...";
    }
}
