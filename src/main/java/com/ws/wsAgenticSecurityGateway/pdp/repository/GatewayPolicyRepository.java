package com.ws.wsAgenticSecurityGateway.pdp.repository;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    /** Tenant-scoped lookup by id — closes the cross-tenant IDOR on read/update/delete/toggle. */
    Optional<GatewayPolicyEntity> findByIdAndWsTenantName(UUID id, String wsTenantName);

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

    // --- Principal read-model (derived on save; indexed by idx_policy_principal) — powers "policies for agent X" ---

    /** Policies that name the agent directly, e.g. {@code principal == Agent::"X"} (kind AGENT, id = agent name). */
    List<GatewayPolicyEntity> findByWsTenantNameAndPrincipalKindAndPrincipalId(
            String wsTenantName, String principalKind, String principalId);

    /** Tenant-wide policies that apply to every agent — the ANY wildcards/guardrails and AGENT_TYPE policies. */
    List<GatewayPolicyEntity> findByWsTenantNameAndPrincipalKindIn(
            String wsTenantName, Collection<String> principalKinds);

    /** Rows saved before the read-model existed — the one-time backfill set. */
    List<GatewayPolicyEntity> findByPrincipalKindIsNull();
}
