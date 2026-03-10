package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentCapabilityProfileAssignmentRepository extends JpaRepository<AgentCapabilityProfileAssignment, UUID> {

    List<AgentCapabilityProfileAssignment> findByAgentId(UUID agentId);

    List<AgentCapabilityProfileAssignment> findByProfileId(UUID profileId);

    Optional<AgentCapabilityProfileAssignment> findByAgentIdAndProfileId(UUID agentId, UUID profileId);

    void deleteByAgentIdAndProfileId(UUID agentId, UUID profileId);

    long countByProfileId(UUID profileId);
}
