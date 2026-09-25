package com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.repository;

import com.ws.wsAgenticSecurityGateway.protocol.mcp.capability.entity.McpResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpResourceRepository extends JpaRepository<McpResourceEntity, UUID> {

    List<McpResourceEntity> findByServerId(UUID serverId);

    Optional<McpResourceEntity> findByPublicName(String publicName);

    @Modifying
    @Transactional
    void deleteByServerId(UUID serverId);

    List<McpResourceEntity> findByServerIdAndWsTenantName(UUID serverId, String wsTenantName);

    Optional<McpResourceEntity> findByPublicNameAndWsTenantName(String publicName, String wsTenantName);

    List<McpResourceEntity> findAllByWsTenantName(String wsTenantName);
}
