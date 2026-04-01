package com.ws.wsAgenticSecurityGateway.common.interceptor;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Extracts the {@code X-WS-Tenant} header from admin REST API requests
 * and stores it in {@link TenantContext} for the duration of the request.
 *
 * <p>Registered for {@code /api/admin/**} paths only — MCP {@code /mcp}
 * traffic resolves its tenant from the session's auth config instead.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);
    public static final String TENANT_HEADER = "X-WS-Tenant";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // OPTIONS (CORS preflight) — let through without tenant
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            log.warn("Missing {} header on {}", TENANT_HEADER, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Missing required header: " + TENANT_HEADER + "\"}");
            return false;
        }

        TenantContext.set(tenant.trim());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
