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

    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.requestCount = s.requestCount + 1, " +
            "s.lastRequestAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId")
    void incrementRequestCount(@Param("sessionId") String sessionId);

    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.sessionId = :sessionId AND s.status = 'CONNECTED'")
    void markDisconnected(@Param("sessionId") String sessionId);

    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.status = 'DISCONNECTED', " +
            "s.disconnectedAt = CURRENT_TIMESTAMP WHERE s.status = 'CONNECTED'")
    void markAllDisconnected();

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent " +
            "WHERE s.status = 'CONNECTED' " +
            "AND COALESCE(s.lastRequestAt, s.connectedAt) < :cutoff")
    List<GatewayAgentSessionEntity> findStaleConnectedSessions(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.status = 'CONNECTED' " +
            "AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentExcluding(
            @Param("agentId") UUID agentId,
            @Param("excludeSessionId") String excludeSessionId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.humanUserId = :humanUserId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndHumanUser(
            @Param("agentId") UUID agentId,
            @Param("humanUserId") UUID humanUserId,
            @Param("excludeSessionId") String excludeSessionId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.nhiId = :nhiId " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndNhi(
            @Param("agentId") UUID agentId,
            @Param("nhiId") UUID nhiId,
            @Param("excludeSessionId") String excludeSessionId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s " +
            "WHERE s.agent.id = :agentId AND s.authIdentity = :authIdentity " +
            "AND s.status = 'CONNECTED' AND s.sessionId != :excludeSessionId")
    List<GatewayAgentSessionEntity> findActiveSessionsForAgentAndAuthIdentity(
            @Param("agentId") UUID agentId,
            @Param("authIdentity") String authIdentity,
            @Param("excludeSessionId") String excludeSessionId);

    @Query("SELECT s.agent.id, COUNT(s) FROM GatewayAgentSessionEntity s GROUP BY s.agent.id")
    List<Object[]> countSessionsByAgent();

    long countByAgentId(UUID agentId);

    List<GatewayAgentSessionEntity> findByHumanUserIdOrderByConnectedAtDesc(UUID humanUserId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByHumanUserIdWithAgent(@Param("humanUserId") UUID humanUserId);

    List<GatewayAgentSessionEntity> findByNhiIdOrderByConnectedAtDesc(UUID nhiId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s JOIN FETCH s.agent WHERE s.nhiId = :nhiId ORDER BY s.connectedAt DESC")
    List<GatewayAgentSessionEntity> findByNhiIdWithAgent(@Param("nhiId") UUID nhiId);

    @Query("SELECT a.approvalStatus FROM GatewayAgentSessionEntity s JOIN s.agent a WHERE s.sessionId = :sessionId")
    Optional<String> findAgentApprovalStatusBySessionId(@Param("sessionId") String sessionId);

    /** The agent lifecycle {@code status} (ACTIVE / DEPROVISIONED) behind a session — DB fallback for the hot-path guard. */
    @Query("SELECT a.status FROM GatewayAgentSessionEntity s JOIN s.agent a WHERE s.sessionId = :sessionId")
    Optional<String> findAgentStatusBySessionId(@Param("sessionId") String sessionId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.humanUserId = :humanUserId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByHumanUserId(@Param("humanUserId") UUID humanUserId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.nhiId = :nhiId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByNhiId(@Param("nhiId") UUID nhiId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.agent.id = :agentId AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByAgentId(@Param("agentId") UUID agentId);

    @Query("SELECT s FROM GatewayAgentSessionEntity s LEFT JOIN FETCH s.agent WHERE s.authIdentity = :authIdentity AND s.status = 'CONNECTED'")
    List<GatewayAgentSessionEntity> findConnectedByAuthIdentity(@Param("authIdentity") String authIdentity);

    @Modifying
    @Query("UPDATE GatewayAgentSessionEntity s SET s.lastRequestAt = CURRENT_TIMESTAMP " +
            "WHERE s.sessionId = :sessionId AND s.status = 'CONNECTED'")
    void updateLastRequestAt(@Param("sessionId") String sessionId);

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
