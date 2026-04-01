package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayAgentRepository extends JpaRepository<GatewayAgentEntity, UUID> {

    Optional<GatewayAgentEntity> findByAgentNameAndAgentVersion(String agentName, String agentVersion);

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

    Optional<GatewayAgentEntity> findByAgentNameAndAgentVersionAndWsTenantName(String agentName, String agentVersion, String wsTenantName);

    List<GatewayAgentEntity> findByStatusAndWsTenantName(String status, String wsTenantName);

    List<GatewayAgentEntity> findByAgentNameAndWsTenantName(String agentName, String wsTenantName);

    List<GatewayAgentEntity> findByApprovalStatusAndWsTenantName(String approvalStatus, String wsTenantName);

    List<GatewayAgentEntity> findAllByWsTenantName(String wsTenantName);

    long countByWsTenantName(String wsTenantName);
}
