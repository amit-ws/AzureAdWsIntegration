package com.ws.wsAgenticSecurityGateway.pdp.repository;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence gateway for Cedar policies.
 */
@Repository
public interface GatewayPolicyRepository extends JpaRepository<GatewayPolicyEntity, UUID> {

    List<GatewayPolicyEntity> findByEnabledTrueOrderByPriorityAsc();

    Optional<GatewayPolicyEntity> findByPolicyName(String policyName);

    Optional<GatewayPolicyEntity> findByCedarPolicyId(String cedarPolicyId);

    List<GatewayPolicyEntity> findByEffect(String effect);

    List<GatewayPolicyEntity> findByTagsContaining(String tag);

    List<GatewayPolicyEntity> findBySource(String source);

    long countByEnabledTrue();

    long countByEffect(String effect);
}
