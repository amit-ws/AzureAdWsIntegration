package com.ws.wsAgenticSecurityGateway.authConfig.repository;

import com.ws.wsAgenticSecurityGateway.authConfig.entity.GatewayAuthConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for gateway auth configuration (single-row, single-tenant).
 */
@Repository
public interface GatewayAuthConfigRepository extends JpaRepository<GatewayAuthConfigEntity, UUID> {

    /** Returns the single auth config row (ordered by creation for determinism). */
    Optional<GatewayAuthConfigEntity> findFirstByOrderByCreatedAtAsc();

    /** Returns the active auth config (enabled=true). */
    Optional<GatewayAuthConfigEntity> findFirstByEnabledTrueOrderByCreatedAtAsc();

    /** Prevents multiple config rows — checks if any row exists besides the given ID. */
    boolean existsByIdNot(UUID id);

    // ── Tenant-scoped queries ───────────────────────────────────────────

    Optional<GatewayAuthConfigEntity> findFirstByWsTenantNameOrderByCreatedAtAsc(String wsTenantName);

    Optional<GatewayAuthConfigEntity> findFirstByEnabledTrueAndWsTenantNameOrderByCreatedAtAsc(String wsTenantName);

    boolean existsByIdNotAndWsTenantName(UUID id, String wsTenantName);
}
