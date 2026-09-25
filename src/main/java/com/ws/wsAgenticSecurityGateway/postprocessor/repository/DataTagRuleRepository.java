package com.ws.wsAgenticSecurityGateway.postprocessor.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.DataTagRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Store for admin egress-classifier rules (tenant-scoped detector configuration, not payloads). */
@Repository
public interface DataTagRuleRepository extends JpaRepository<DataTagRuleEntity, UUID> {

    List<DataTagRuleEntity> findByWsTenantNameOrderByCreatedAtDesc(String wsTenantName);

    List<DataTagRuleEntity> findByWsTenantNameAndEnabledTrue(String wsTenantName);

    Optional<DataTagRuleEntity> findByIdAndWsTenantName(UUID id, String wsTenantName);

    /** For per-tenant name-uniqueness — any rule with this name (case-insensitive) in the tenant. */
    List<DataTagRuleEntity> findByWsTenantNameAndNameIgnoreCase(String wsTenantName, String name);

    /** For template-pack dedup — the template ids already installed in this tenant. */
    List<DataTagRuleEntity> findByWsTenantNameAndSourceTemplateIdIsNotNull(String wsTenantName);
}
