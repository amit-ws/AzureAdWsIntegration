package com.ws.wsAgenticSecurity.registry.repository;

import com.ws.wsAgenticSecurity.registry.entity.McpPromptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link McpPromptEntity} — prompts discovered from enterprise MCP servers.
 */
@Repository
public interface McpPromptRepository extends JpaRepository<McpPromptEntity, UUID> {

    List<McpPromptEntity> findByServerId(UUID serverId);

    Optional<McpPromptEntity> findByPublicName(String publicName);

    @Modifying
    @Transactional
    void deleteByServerId(UUID serverId);
}
