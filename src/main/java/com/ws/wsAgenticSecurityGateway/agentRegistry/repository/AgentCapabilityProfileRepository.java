package com.ws.wsAgenticSecurityGateway.agentRegistry.repository;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.AgentCapabilityProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentCapabilityProfileRepository extends JpaRepository<AgentCapabilityProfile, UUID> {

    Optional<AgentCapabilityProfile> findByName(String name);

    List<AgentCapabilityProfile> findByIsTemplateTrue();

    Optional<AgentCapabilityProfile> findByNameAndWsTenantName(String name, String wsTenantName);

    List<AgentCapabilityProfile> findByIsTemplateTrueAndWsTenantName(String wsTenantName);

    List<AgentCapabilityProfile> findAllByWsTenantName(String wsTenantName);
}
