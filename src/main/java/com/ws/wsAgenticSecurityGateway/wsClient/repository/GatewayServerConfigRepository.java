package com.ws.wsAgenticSecurityGateway.wsClient.repository;

import com.ws.wsAgenticSecurityGateway.wsClient.entity.GatewayServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence gateway for MCP server configuration records.
 */
@Repository
public interface GatewayServerConfigRepository extends JpaRepository<GatewayServerConfigEntity, UUID> {

    Optional<GatewayServerConfigEntity> findByServerName(String serverName);

    boolean existsByServerName(String serverName);

    /** Startup query — only enabled servers with auto-connect ON. */
    List<GatewayServerConfigEntity> findByEnabledTrueAndAutoConnectTrue();

    /** Admin list — ordered alphabetically. */
    List<GatewayServerConfigEntity> findAllByOrderByServerNameAsc();

    // ── Tenant-scoped queries ───────────────────────────────────────────

    Optional<GatewayServerConfigEntity> findByServerNameAndWsTenantName(String serverName, String wsTenantName);

    boolean existsByServerNameAndWsTenantName(String serverName, String wsTenantName);

    List<GatewayServerConfigEntity> findByEnabledTrueAndAutoConnectTrueAndWsTenantName(String wsTenantName);

    List<GatewayServerConfigEntity> findAllByWsTenantNameOrderByServerNameAsc(String wsTenantName);
}
