package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentSessionEntity;
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
public interface GatewayAgentSessionRepository extends JpaRepository<GatewayAgentSessionEntity, UUID> {

    List<GatewayAgentSessionEntity> findByAgentIdOrderByConnectedAtDesc(UUID agentId);

    Optional<GatewayAgentSessionEntity> findBySessionId(String sessionId);

    List<GatewayAgentSessionEntity> findByStatus(String status);

    /** Atomic request count increment — called async on every orchestration. */
    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.requestCount = s.requestCount + 1, " +
            "s.lastRequestAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId")
    void incrementRequestCount(@Param("sessionId") String sessionId);

    /** Mark a session as disconnected — called when agent disconnects. */
    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId AND s.status = 'CONNECTED'")
    void markDisconnected(@Param("sessionId") String sessionId);

    /** Cleanup on startup — mark all orphaned CONNECTED sessions as DISCONNECTED. */
    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED'")
    void markAllDisconnected();

    /**
     * Find CONNECTED sessions that have been idle beyond the cutoff timestamp.
     * Uses COALESCE: if lastRequestAt is null, falls back to connectedAt (no requests yet).
     * JOIN FETCH prevents N+1 when iterating results and reading agent name.
     */
    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent " +
            "WHERE s.status = 'CONNECTED' " +
            "AND COALESCE(s.lastRequestAt, s.connectedAt) < :cutoff")
    List<GatewayAgentSessionEntity> findStaleConnectedSessions(@Param("cutoff") LocalDateTime cutoff);
}
