package com.ws.wsAgenticSecurityGateway.sts.repository;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsSessionRevocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayStsSessionRevocationRepository
        extends JpaRepository<GatewayStsSessionRevocationEntity, UUID> {

    Optional<GatewayStsSessionRevocationEntity> findBySessionId(String sessionId);

    /** Still-active session revocations — for the in-memory cache + admin list. */
    List<GatewayStsSessionRevocationEntity> findByExpiresAtAfter(LocalDateTime now);

    /** Naturally-expired session revocations — the purge sweep evicts exactly these keys. */
    List<GatewayStsSessionRevocationEntity> findByExpiresAtBefore(LocalDateTime cutoff);

    List<GatewayStsSessionRevocationEntity> findByWsTenantNameAndExpiresAtAfter(String wsTenantName, LocalDateTime now);

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
