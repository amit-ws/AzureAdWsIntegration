package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.repository;

import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.entity.A2aAgentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface A2aAgentRepository extends JpaRepository<A2aAgentEntity, UUID> {

    Optional<A2aAgentEntity> findByNameAndWsTenantName(String name, String wsTenantName);

    List<A2aAgentEntity> findByWsTenantName(String wsTenantName);
}
