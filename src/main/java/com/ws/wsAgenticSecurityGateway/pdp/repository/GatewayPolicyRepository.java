package com.ws.wsAgenticSecurityGateway.pdp.repository;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    List<GatewayPolicyEntity> findByEnabledTrueAndWsTenantNameOrderByPriorityAsc(String wsTenantName);

    Optional<GatewayPolicyEntity> findByPolicyNameAndWsTenantName(String policyName, String wsTenantName);

    Optional<GatewayPolicyEntity> findByCedarPolicyIdAndWsTenantName(String cedarPolicyId, String wsTenantName);

    List<GatewayPolicyEntity> findByEffectAndWsTenantName(String effect, String wsTenantName);

    List<GatewayPolicyEntity> findByTagsContainingAndWsTenantName(String tag, String wsTenantName);

    List<GatewayPolicyEntity> findBySourceAndWsTenantName(String source, String wsTenantName);

    long countByEnabledTrueAndWsTenantName(String wsTenantName);

    long countByEffectAndWsTenantName(String effect, String wsTenantName);

    List<GatewayPolicyEntity> findAllByWsTenantName(String wsTenantName);

    /** Distinct tenants that already own at least one policy — the set to seed default guardrails for. */
    @Query("SELECT DISTINCT p.wsTenantName FROM GatewayPolicyEntity p")
    List<String> findDistinctWsTenantName();
}
