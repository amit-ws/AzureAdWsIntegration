package com.ws.wsAgenticSecurityGateway.authConfig.repository;

import com.ws.wsAgenticSecurityGateway.authConfig.entity.GatewayAuthConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayAuthConfigRepository extends JpaRepository<GatewayAuthConfigEntity, UUID> {

    Optional<GatewayAuthConfigEntity> findFirstByOrderByCreatedAtAsc();

    Optional<GatewayAuthConfigEntity> findFirstByEnabledTrueOrderByCreatedAtAsc();

    boolean existsByIdNot(UUID id);

    Optional<GatewayAuthConfigEntity> findFirstByWsTenantNameOrderByCreatedAtAsc(String wsTenantName);

    Optional<GatewayAuthConfigEntity> findFirstByEnabledTrueAndWsTenantNameOrderByCreatedAtAsc(String wsTenantName);

    boolean existsByIdNotAndWsTenantName(UUID id, String wsTenantName);
}
