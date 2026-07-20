package com.ws.wsAgenticSecurityGateway.capabilityRegistry.repository;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface McpServerRepository extends JpaRepository<McpServerEntity, UUID> {

    Optional<McpServerEntity> findByServerConfigName(String serverConfigName);

    List<McpServerEntity> findByStatus(String status);

    Optional<McpServerEntity> findByServerConfigNameAndWsTenantName(String serverConfigName, String wsTenantName);

    List<McpServerEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    List<McpServerEntity> findAllByWsTenantName(String wsTenantName);
}
