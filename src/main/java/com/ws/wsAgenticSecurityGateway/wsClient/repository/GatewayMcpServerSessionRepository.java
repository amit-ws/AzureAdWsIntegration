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

/**
 * Persistence gateway for southbound MCP server session records.
 */
@Repository
public interface GatewayMcpServerSessionRepository extends JpaRepository<GatewayMcpServerSessionEntity, UUID> {

    Optional<GatewayMcpServerSessionEntity> findByServerNameAndStatus(String serverName, String status);

    Optional<GatewayMcpServerSessionEntity> findBySessionId(String sessionId);

    List<GatewayMcpServerSessionEntity> findByStatus(String status);

    /** Mark a specific server's CONNECTED session as DISCONNECTED. */
    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.serverName = :serverName AND s.status = 'CONNECTED'")
    int markDisconnected(@Param("serverName") String serverName);

    /** Cleanup on startup — mark all orphaned CONNECTED sessions as DISCONNECTED. */
    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED'")
    int markAllDisconnected();

    /** Atomic request count increment — called on every orchestration forwarding. */
    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.totalRequests = s.totalRequests + 1, " +
            "s.lastActivityAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId")
    int incrementRequestCount(@Param("sessionId") String sessionId);

    /** Update health check status for a server. */
    @Modifying
    @Transactional
    @Query("UPDATE GatewayMcpServerSessionEntity s SET s.lastHealthCheckAt = CURRENT_TIMESTAMP, " +
            "s.healthCheckStatus = :status, " +
            "s.consecutiveFailures = :failures WHERE s.serverName = :serverName AND s.status = 'CONNECTED'")
    int updateHealthCheck(@Param("serverName") String serverName,
                          @Param("status") String status,
                          @Param("failures") int failures);
}
