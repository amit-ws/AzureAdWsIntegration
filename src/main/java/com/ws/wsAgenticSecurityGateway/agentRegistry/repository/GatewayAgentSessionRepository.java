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

    /** Identity-scoped: find CONNECTED sessions for same agent + same human (exclude new session). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.humanUserId = :humanUserId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndHumanUser(
            @Param("agentId") UUID agentId,
            @Param("humanUserId") UUID humanUserId,
            @Param("excludeSessionId") String excludeSessionId);

    /** Identity-scoped: find CONNECTED sessions for same agent + same NHI (exclude new session). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.nhiId = :nhiId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndNhi(
            @Param("agentId") UUID agentId,
            @Param("nhiId") UUID nhiId,
            @Param("excludeSessionId") String excludeSessionId);

    /** Identity-scoped fallback: find CONNECTED sessions for same agent + same authIdentity (exclude new session). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.authIdentity = :authIdentity " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndAuthIdentity(
            @Param("agentId") UUID agentId,
            @Param("authIdentity") String authIdentity,
            @Param("excludeSessionId") String excludeSessionId);

    /** Batch count of all sessions grouped by agent — for accurate totalSessions display. */
    @Query("SELECT s.agent.id, COUNT(s) FROM GatewayAgentSessionEntity s GROUP BY s.agent.id")
    List<Object[]> countSessionsByAgent();

    /** Count total sessions for a specific agent. */
    long countByAgentId(UUID agentId);

    /** Find all sessions for a specific human user (for human-user detail page). */
    List<GatewayAgentSessionEntity> findByHumanUserIdOrderByConnectedAtDesc(UUID humanUserId);

    /** Find sessions for a human user with agent eagerly loaded (avoids LazyInitializationException in metadata builders). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByHumanUserIdWithAgent(@Param("humanUserId") UUID humanUserId);

    /** Find all sessions for a specific NHI (for NHI detail page). */
    List<GatewayAgentSessionEntity> findByNhiIdOrderByConnectedAtDesc(UUID nhiId);

    /** Find sessions for an NHI with agent eagerly loaded. */
    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.nhiId = :nhiId ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByNhiIdWithAgent(@Param("nhiId") UUID nhiId);

    /** Returns the agent's approval_status for a given session ID — single JOIN query, no lazy loading. */
    @Query("SELECT a.approvalStatus FROM GatewayAgentSessionEntity s JOIN s.agent a WHERE s.sessionId = :sessionId")
    Optional<String> findAgentApprovalStatusBySessionId(@Param("sessionId") String sessionId);

    /** Find all CONNECTED sessions for a specific human user (for proactive session termination on block). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByHumanUserId(@Param("humanUserId") UUID humanUserId);

    /** Find all CONNECTED sessions for a specific NHI (for proactive session termination on block). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.nhiId = :nhiId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByNhiId(@Param("nhiId") UUID nhiId);

    /** Find all CONNECTED sessions for a specific agent (for proactive session termination on block). */
    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.agent.id = :agentId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByAgentId(@Param("agentId") UUID agentId);

    /**
     * Find all CONNECTED sessions by authIdentity (JWT subject).
     * Fallback for proactive session termination when humanUserId/nhiId columns are null
     * (e.g., token misclassification during initialize).
     */
    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.authIdentity = :authIdentity AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByAuthIdentity(@Param("authIdentity") String authIdentity);

    /**
     * Layer 2 — Smart Idle Timeout: lightweight activity timestamp update.
     * Called at response completion to keep sessions alive during long-running tool calls.
     * Does NOT increment requestCount (that's done separately at request start).
     */
    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.lastRequestAt = CURRENT_TIMESTAMP " +
            "WHERE s.sessionId = :sessionId AND s.status = 'CONNECTED'")
    void updateLastRequestAt(@Param("sessionId") String sessionId);

    // ── Tenant-scoped queries ───────────────────────────────────────────

    List<GatewayAgentSessionEntity> findByAgentIdAndWsTenantNameOrderByConnectedAtDesc(UUID agentId, String wsTenantName);

    Optional<GatewayAgentSessionEntity> findBySessionIdAndWsTenantName(String sessionId, String wsTenantName);

    List<GatewayAgentSessionEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent " +
            "WHERE s.status = 'CONNECTED' " +
            "AND COALESCE(s.lastRequestAt, s.connectedAt) < :cutoff " +
            "AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findStaleConnectedSessionsByTenant(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.status = 'CONNECTED' " +
            "AND s.sessionId != :excludeSessionId AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentExcludingByTenant(
            @Param("agentId") UUID agentId,
            @Param("excludeSessionId") String excludeSessionId,
            @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.humanUserId = :humanUserId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndHumanUserByTenant(
            @Param("agentId") UUID agentId,
            @Param("humanUserId") UUID humanUserId,
            @Param("excludeSessionId") String excludeSessionId,
            @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.nhiId = :nhiId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndNhiByTenant(
            @Param("agentId") UUID agentId,
            @Param("nhiId") UUID nhiId,
            @Param("excludeSessionId") String excludeSessionId,
            @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.authIdentity = :authIdentity " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndAuthIdentityByTenant(
            @Param("agentId") UUID agentId,
            @Param("authIdentity") String authIdentity,
            @Param("excludeSessionId") String excludeSessionId,
            @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s.agent.id, COUNT(s) FROM GatewayAgentSessionEntity s WHERE s.wsTenantName = :wsTenantName GROUP BY s.agent.id")
    List<Object[]> countSessionsByAgentByTenant(@Param("wsTenantName") String wsTenantName);

    long countByAgentIdAndWsTenantName(UUID agentId, String wsTenantName);

    List<GatewayAgentSessionEntity> findByHumanUserIdAndWsTenantNameOrderByConnectedAtDesc(UUID humanUserId, String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId AND s.wsTenantName = :wsTenantName ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByHumanUserIdWithAgentByTenant(@Param("humanUserId") UUID humanUserId, @Param("wsTenantName") String wsTenantName);

    List<GatewayAgentSessionEntity> findByNhiIdAndWsTenantNameOrderByConnectedAtDesc(UUID nhiId, String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.nhiId = :nhiId AND s.wsTenantName = :wsTenantName ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByNhiIdWithAgentByTenant(@Param("nhiId") UUID nhiId, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT a.approvalStatus FROM GatewayAgentSessionEntity s JOIN s.agent a WHERE s.sessionId = :sessionId AND s.wsTenantName = :wsTenantName")
    Optional<String> findAgentApprovalStatusBySessionIdAndTenant(@Param("sessionId") String sessionId, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findConnectedByHumanUserIdAndTenant(@Param("humanUserId") UUID humanUserId, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.nhiId = :nhiId AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findConnectedByNhiIdAndTenant(@Param("nhiId") UUID nhiId, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.agent.id = :agentId AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findConnectedByAgentIdAndTenant(@Param("agentId") UUID agentId, @Param("wsTenantName") String wsTenantName);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.authIdentity = :authIdentity AND s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    List<GatewayAgentSessionEntity> findConnectedByAuthIdentityAndTenant(@Param("authIdentity") String authIdentity, @Param("wsTenantName") String wsTenantName);

    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED' AND s.wsTenantName = :wsTenantName")
    void markAllDisconnectedByTenant(@Param("wsTenantName") String wsTenantName);
}
