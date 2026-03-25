package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayNhiEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for Non-Human Identity (NHI) records.
 * Mirrors {@link GatewayHumanUserRepository} for the automated identity plane.
 */
@Repository
public interface GatewayNhiRepository extends JpaRepository<GatewayNhiEntity, UUID> {

    /** Find by stable IdP subject (JWT sub) — the NHI's unique identity anchor. */
    Optional<GatewayNhiEntity> findByIdpSubject(String idpSubject);

    /** Find by OAuth2 client_id (JWT azp). */
    Optional<GatewayNhiEntity> findByClientId(String clientId);

    /** Find all NHIs with a given status (ACTIVE / BLOCKED). */
    List<GatewayNhiEntity> findByStatus(String status);

    /** Fuzzy search by service name or client ID — for admin lookup. */
    List<GatewayNhiEntity> findByServiceNameContainingIgnoreCaseOrClientIdContainingIgnoreCase(
            String serviceName, String clientId);

    /** Count NHIs active since a given timestamp — for "active today" stats. */
    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.lastSeenAt > :since")
    long countActiveSince(@Param("since") LocalDateTime since);

    /** Count blocked NHIs. */
    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.status = 'BLOCKED'")
    long countBlocked();

    /** Atomic request count increment — called async on every AUTOMATED_AGENT orchestration. */
    @Modifying
    @Query("UPDATE GatewayNhiEntity n SET n.totalRequests = n.totalRequests + 1, " +
            "n.lastSeenAt = CURRENT_TIMESTAMP WHERE n.id = :nhiId")
    void incrementRequestCount(@Param("nhiId") UUID nhiId);
}
