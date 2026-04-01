package com.ws.wsAgenticSecurityGateway.common.context;

/**
 * ThreadLocal holder for the current tenant (wsTenantName).
 *
 * <p>Set by {@link com.ws.wsAgenticSecurityGateway.common.interceptor.TenantInterceptor}
 * on admin REST API requests (from {@code X-WS-Tenant} header), and by
 * {@link com.ws.wsAgenticSecurityGateway.wsServer.transport.HttpMcpAuditFilter}
 * on MCP requests (resolved from the session's auth config).
 *
 * <p><strong>Always cleared after the request completes</strong> to prevent
 * thread-pool contamination.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static String get() {
        return CURRENT.get();
    }

    public static void set(String tenantName) {
        CURRENT.set(tenantName);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
