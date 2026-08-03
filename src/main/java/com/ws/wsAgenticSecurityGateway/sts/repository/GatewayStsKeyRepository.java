package com.ws.wsAgenticSecurityGateway.sts.repository;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayStsKeyRepository extends JpaRepository<GatewayStsKeyEntity, UUID> {

    Optional<GatewayStsKeyEntity> findFirstByWsTenantNameAndStatus(String wsTenantName, String status);

    List<GatewayStsKeyEntity> findByWsTenantNameAndStatusIn(String wsTenantName, Collection<String> statuses);

    List<GatewayStsKeyEntity> findByWsTenantNameOrderByCreatedAtDesc(String wsTenantName);

    /** All keys of a status for a tenant — used to demote the current ACTIVE key(s) on rotation. */
    List<GatewayStsKeyEntity> findByWsTenantNameAndStatus(String wsTenantName, String status);

    /** RETIRING keys whose grace window has elapsed (across all tenants) — used by the purge sweep. */
    List<GatewayStsKeyEntity> findByStatusAndRetiredAtBefore(String status, LocalDateTime cutoff);
}
