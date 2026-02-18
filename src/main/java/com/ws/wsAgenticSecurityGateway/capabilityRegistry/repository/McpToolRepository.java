package com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpToolEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link McpToolEntity} — tools discovered from enterprise MCP servers.
 */
@Repository
public interface McpToolRepository extends JpaRepository<McpToolEntity, UUID> {

    List<McpToolEntity> findByServerId(UUID serverId);

    Optional<McpToolEntity> findByPublicName(String publicName);

    @Modifying
    @Transactional
    void deleteByServerId(UUID serverId);
}
