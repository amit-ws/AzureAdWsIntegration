package com.ws.wsAgenticSecurityGateway.common.context;

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
