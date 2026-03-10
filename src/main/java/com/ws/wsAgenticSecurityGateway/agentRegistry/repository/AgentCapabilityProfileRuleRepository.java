package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfileRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentCapabilityProfileRuleRepository extends JpaRepository<AgentCapabilityProfileRule, UUID> {

    List<AgentCapabilityProfileRule> findByProfileId(UUID profileId);

    void deleteByProfileId(UUID profileId);
}
