package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayHumanUserRepository extends JpaRepository<GatewayHumanUserEntity, UUID> {

    Optional<GatewayHumanUserEntity> findByIdpSubject(String idpSubject);

    Optional<GatewayHumanUserEntity> findByPreferredUsername(String preferredUsername);

    List<GatewayHumanUserEntity> findByStatus(String status);

    Optional<GatewayHumanUserEntity> findByEmail(String email);

    List<GatewayHumanUserEntity> findByPreferredUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String email);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.lastSeenAt > :since")
    long countActiveHumansSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.status = 'BLOCKED'")
    long countBlocked();

    /** Atomic request count increment — called async on every orchestration for human-delegated sessions. */
    @Modifying
    @Query("UPDATE GatewayHumanUserEntity h SET h.totalRequests = h.totalRequests + 1, " +
            "h.lastSeenAt = CURRENT_TIMESTAMP WHERE h.id = :humanUserId")
    void incrementRequestCount(@Param("humanUserId") UUID humanUserId);

    // ── Tenant-scoped queries ───────────────────────────────────────────

    Optional<GatewayHumanUserEntity> findByIdpSubjectAndWsTenantName(String idpSubject, String wsTenantName);

    Optional<GatewayHumanUserEntity> findByPreferredUsernameAndWsTenantName(String preferredUsername, String wsTenantName);

    List<GatewayHumanUserEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    Optional<GatewayHumanUserEntity> findByEmailAndWsTenantName(String email, String wsTenantName);

    List<GatewayHumanUserEntity> findByWsTenantNameAndPreferredUsernameContainingIgnoreCaseOrWsTenantNameAndEmailContainingIgnoreCase(
            String wsTenantName1, String username, String wsTenantName2, String email);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.lastSeenAt > :since AND h.wsTenantName = :wsTenantName")
    long countActiveHumansSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT COUNT(h) FROM GatewayHumanUserEntity h WHERE h.status = 'BLOCKED' AND h.wsTenantName = :wsTenantName")
    long countBlockedByTenant(@Param("wsTenantName") String wsTenantName);

    List<GatewayHumanUserEntity> findAllByWsTenantName(String wsTenantName);

    long countByWsTenantName(String wsTenantName);

    @Query("SELECT h FROM GatewayHumanUserEntity h WHERE h.wsTenantName = :tenant " +
            "AND (LOWER(h.preferredUsername) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(h.email) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<GatewayHumanUserEntity> searchByTenant(@Param("q") String query, @Param("tenant") String tenant);
}
