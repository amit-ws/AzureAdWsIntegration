package com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link McpResourceEntity} — resources discovered from enterprise MCP servers.
 */
@Repository
public interface McpResourceRepository extends JpaRepository<McpResourceEntity, UUID> {

    List<McpResourceEntity> findByServerId(UUID serverId);

    Optional<McpResourceEntity> findByPublicName(String publicName);

    @Modifying
    @Transactional
    void deleteByServerId(UUID serverId);

    // ── Tenant-scoped queries ───────────────────────────────────────────

    List<McpResourceEntity> findByServerIdAndWsTenantName(UUID serverId, String wsTenantName);

    Optional<McpResourceEntity> findByPublicNameAndWsTenantName(String publicName, String wsTenantName);

    List<McpResourceEntity> findAllByWsTenantName(String wsTenantName);
}
