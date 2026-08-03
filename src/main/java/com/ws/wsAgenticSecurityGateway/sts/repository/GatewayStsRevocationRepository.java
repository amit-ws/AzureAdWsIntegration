package com.ws.wsAgenticSecurityGateway.sts.repository;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRevocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayStsRevocationRepository extends JpaRepository<GatewayStsRevocationEntity, UUID> {

    boolean existsByJti(String jti);

    Optional<GatewayStsRevocationEntity> findByJti(String jti);

    /** Still-active revocations (token not yet naturally expired) — for the in-memory cache + admin list. */
    List<GatewayStsRevocationEntity> findByExpiresAtAfter(LocalDateTime now);

    /** Naturally-expired revocations — the purge sweep evicts exactly these keys (never a snapshot diff). */
    List<GatewayStsRevocationEntity> findByExpiresAtBefore(LocalDateTime cutoff);

    List<GatewayStsRevocationEntity> findByWsTenantNameAndExpiresAtAfter(String wsTenantName, LocalDateTime now);

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
