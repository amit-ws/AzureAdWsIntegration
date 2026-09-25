package com.ws.wsAgenticSecurityGateway.common.listener;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the global tenant-stamping listener — pure logic, no Spring/DB needed.
 */
class TenantEntityListenerTest {

    /** Stand-in for a tenant-scoped entity. */
    static class TenantEntity {
        String wsTenantName;
    }

    /** Stand-in for an entity that is NOT tenant-scoped. */
    static class PlainEntity {
        String other;
    }

    private final TenantEntityListener listener = new TenantEntityListener();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void stamps_tenant_when_null_and_context_present() {
        TenantContext.set("amitdev.local");
        TenantEntity e = new TenantEntity();

        listener.stampTenant(e);

        assertThat(e.wsTenantName).isEqualTo("amitdev.local");
    }

    @Test
    void does_not_overwrite_existing_tenant() {
        TenantContext.set("amitdev.local");
        TenantEntity e = new TenantEntity();
        e.wsTenantName = "already.set";

        listener.stampTenant(e);

        assertThat(e.wsTenantName).isEqualTo("already.set");
    }

    @Test
    void does_nothing_when_no_tenant_context() {
        TenantEntity e = new TenantEntity();

        listener.stampTenant(e);

        assertThat(e.wsTenantName).isNull();
    }

    @Test
    void ignores_entity_without_tenant_field() {
        TenantContext.set("amitdev.local");
        PlainEntity e = new PlainEntity();

        listener.stampTenant(e); // must not throw

        assertThat(e.other).isNull();
    }
}
