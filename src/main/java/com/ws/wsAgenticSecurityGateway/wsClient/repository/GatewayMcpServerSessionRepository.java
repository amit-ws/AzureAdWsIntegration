package com.ws.wsAgenticSecurityGateway.wsClient.repository;

import com.ws.wsAgenticSecurityGateway.wsClient.entity.GatewayMcpServerSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayMcpServerSessionRepository extends JpaRepository<GatewayMcpServerSessionEntity, UUID> {

    Optional<GatewayMcpServerSessionEntity> findByServerNameAndStatus(String serverName, String status);

    Optional<GatewayMcpServerSessionEntity> findBySessionId(String sessionId);

    List<GatewayMcpServerSessionEntity> findByStatus(String status);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.serverName = :serverName AND s.status = 'CONNECTED'")
    int markDisconnected(@Param("serverName") String serverName);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED'")
    int markAllDisconnected();

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.totalRequests = s.totalRequests + 1, " +
            "s.lastActivityAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId")
    int incrementRequestCount(@Param("sessionId") String sessionId);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.lastHealthCheckAt = CURRENT_TIMESTAMP, " +
            "s.healthCheckStatus = :status, " +
            "s.consecutiveFailures = :failures WHERE s.serverName = :serverName AND s.status = 'CONNECTED'")
    int updateHealthCheck(@Param("serverName") String serverName,
                          @Param("status") String status,
                          @Param("failures") int failures);

    Optional<GatewayMcpServerSessionEntity> findByServerNameAndStatusAndWsTenantName(String serverName, String status, String wsTenantName);

    Optional<GatewayMcpServerSessionEntity> findBySessionIdAndWsTenantName(String sessionId, String wsTenantName);

    List<GatewayMcpServerSessionEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.serverName = :serverName AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    int markDisconnectedByTenant(@Param("serverName") String serverName, @Param("wsTenantName") String wsTenantName);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    int markAllDisconnectedByTenant(@Param("wsTenantName") String wsTenantName);

    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.lastHealthCheckAt = CURRENT_TIMESTAMP, " +
            "s.healthCheckStatus = :status, " +
            "s.consecutiveFailures = :failures WHERE s.serverName = :serverName AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    int updateHealthCheckByTenant(@Param("serverName") String serverName,
                                  @Param("status") String status,
                                  @Param("failures") int failures,
                                  @Param("wsTenantName") String wsTenantName);
}
