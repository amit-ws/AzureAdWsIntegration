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

@Repository
public interface GatewayNhiRepository extends JpaRepository<GatewayNhiEntity, UUID> {

    Optional<GatewayNhiEntity> findByIdpSubject(String idpSubject);

    Optional<GatewayNhiEntity> findByClientId(String clientId);

    List<GatewayNhiEntity> findByStatus(String status);

    List<GatewayNhiEntity> findByServiceNameContainingIgnoreCaseOrClientIdContainingIgnoreCase(
            String serviceName, String clientId);

    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.lastSeenAt > :since")
    long countActiveSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.status = 'BLOCKED'")
    long countBlocked();

    @Modifying
    @Query("UPDATE GatewayNhiEntity n SET n.totalRequests = n.totalRequests + 1, " +
            "n.lastSeenAt = CURRENT_TIMESTAMP WHERE n.id = :nhiId")
    void incrementRequestCount(@Param("nhiId") UUID nhiId);

    Optional<GatewayNhiEntity> findByIdpSubjectAndWsTenantName(String idpSubject, String wsTenantName);

    Optional<GatewayNhiEntity> findByClientIdAndWsTenantName(String clientId, String wsTenantName);

    List<GatewayNhiEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    List<GatewayNhiEntity> findByWsTenantNameAndServiceNameContainingIgnoreCaseOrWsTenantNameAndClientIdContainingIgnoreCase(
            String wsTenantName1, String serviceName, String wsTenantName2, String clientId);

    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.lastSeenAt > :since AND n.wsTenantName = :wsTenantName")
    long countActiveSinceByTenant(@Param("since") LocalDateTime since, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT COUNT(n) FROM GatewayNhiEntity n WHERE n.status = 'BLOCKED' AND n.wsTenantName = :wsTenantName")
    long countBlockedByTenant(@Param("wsTenantName") String wsTenantName);

    List<GatewayNhiEntity> findAllByWsTenantName(String wsTenantName);

    long countByWsTenantName(String wsTenantName);

    @Query("SELECT n FROM GatewayNhiEntity n WHERE n.wsTenantName = :tenant " +
            "AND (LOWER(n.serviceName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "OR LOWER(n.clientId) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<GatewayNhiEntity> searchByTenant(@Param("q") String query, @Param("tenant") String tenant);
}
