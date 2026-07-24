package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.repository;

import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.entity.GatewayServerConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayServerConfigRepository extends JpaRepository<GatewayServerConfigEntity, UUID> {

    Optional<GatewayServerConfigEntity> findByServerName(String serverName);

    boolean existsByServerName(String serverName);

    List<GatewayServerConfigEntity> findByEnabledTrueAndAutoConnectTrue();

    List<GatewayServerConfigEntity> findAllByOrderByServerNameAsc();

    Optional<GatewayServerConfigEntity> findByServerNameAndWsTenantName(String serverName, String wsTenantName);

    boolean existsByServerNameAndWsTenantName(String serverName, String wsTenantName);

    List<GatewayServerConfigEntity> findByEnabledTrueAndAutoConnectTrueAndWsTenantName(String wsTenantName);

    List<GatewayServerConfigEntity> findAllByWsTenantNameOrderByServerNameAsc(String wsTenantName);
}
