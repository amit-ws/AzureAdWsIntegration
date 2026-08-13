package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GatewayAgentRepository extends JpaRepository<GatewayAgentEntity, UUID> {

    List<GatewayAgentEntity> findByStatus(String status);

    List<GatewayAgentEntity> findByAgentName(String agentName);

    List<GatewayAgentEntity> findByApprovalStatus(String approvalStatus);

    @Modifying
    @Query("UPDATE GatewayAgentEntity a SET a.totalSessions = a.totalSessions + 1, " +
            "a.lastSeenAt = CURRENT_TIMESTAMP WHERE a.id = :agentId")
    void incrementSessionCount(@Param("agentId") UUID agentId);

    @Modifying
    @Query("UPDATE GatewayAgentEntity a SET a.totalRequests = a.totalRequests + 1, " +
            "a.lastSeenAt = CURRENT_TIMESTAMP WHERE a.id = :agentId")
    void incrementRequestCount(@Param("agentId") UUID agentId);

    List<GatewayAgentEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    List<GatewayAgentEntity> findByAgentNameAndWsTenantName(String agentName, String wsTenantName);

    List<GatewayAgentEntity> findByApprovalStatusAndWsTenantName(String approvalStatus, String wsTenantName);

    List<GatewayAgentEntity> findAllByWsTenantName(String wsTenantName);

    long countByWsTenantName(String wsTenantName);

    // A2A-endpoint agents (the canonical replacement for the former gateway_a2a_agent store). The no-tenant
    // variant feeds the startup AgentSource reconcile (which runs without a tenant context).
    List<GatewayAgentEntity> findBySpeaksA2aTrue();

    List<GatewayAgentEntity> findByWsTenantNameAndSpeaksA2aTrue(String wsTenantName);

    /** Agents in the tenant advertising a given A2A base URL — used to reject registering the same URL twice. */
    List<GatewayAgentEntity> findByWsTenantNameAndA2aBaseUrl(String wsTenantName, String a2aBaseUrl);
}
