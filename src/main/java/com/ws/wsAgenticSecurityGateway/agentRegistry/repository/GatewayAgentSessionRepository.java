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

    /**
     * Layer 1 — Active Session Replacement: find all CONNECTED sessions for an agent
     * EXCEPT the newly created one. Used to disconnect stale/zombie sessions on reconnect.
     */
    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.status = 'CONNECTED' " +
            "AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentExcluding(
            @Param("agentId") UUID agentId,
            @Param("excludeSessionId") String excludeSessionId);

    /** Batch count of all sessions grouped by agent — for accurate totalSessions display. */
    @Query("SELECT s.agent.id, COUNT(s) FROM GatewayAgentSessionEntity s GROUP BY s.agent.id")
    List<Object[]> countSessionsByAgent();

    /** Count total sessions for a specific agent. */
    long countByAgentId(UUID agentId);

    /**
     * Layer 2 — Smart Idle Timeout: lightweight activity timestamp update.
     * Called at response completion to keep sessions alive during long-running tool calls.
     * Does NOT increment requestCount (that's done separately at request start).
     */
    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.lastRequestAt = CURRENT_TIMESTAMP " +
            "WHERE s.sessionId = :sessionId AND s.status = 'CONNECTED'")
    void updateLastRequestAt(@Param("sessionId") String sessionId);
}
