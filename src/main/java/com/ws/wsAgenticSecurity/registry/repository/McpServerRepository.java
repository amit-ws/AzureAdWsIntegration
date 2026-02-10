package com.ws.wsAgenticSecurity.registry.repository;

import com.ws.wsAgenticSecurity.registry.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link McpServerEntity} — the registered MCP server records.
 */
@Repository
public interface McpServerRepository extends JpaRepository<McpServerEntity, UUID> {

    Optional<McpServerEntity> findByServerConfigName(String serverConfigName);

    List<McpServerEntity> findByStatus(String status);
}
