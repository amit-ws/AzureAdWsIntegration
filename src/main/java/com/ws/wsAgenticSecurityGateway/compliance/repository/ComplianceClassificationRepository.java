package com.ws.wsAgenticSecurityGateway.compliance.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Compliance-owned aggregate over the egress data-classification ledger ({@code gateway_response_classification})
 * plus the active-rule count from {@code data_tag_rule}. These are the post-processor metrics that feed the
 * data-integrity / confidentiality controls (SOX ICFR, and available to any framework). Carried in this package so
 * the compliance module stays self-contained. Native SQL — the bound entity is only a formality for Spring Data.
 */
@Repository
public interface ComplianceClassificationRepository
        extends JpaRepository<GatewayResponseClassificationEntity, UUID> {

    /**
     * Tenant egress-classification coverage for the evidence pack. Single row:
     * {@code [total, sensitive, restricted, confidential, financial, injections, human_attributed, first_at, last_at]}
     * — the seven counts are Long, the last two are Timestamp. "sensitive" = CONFIDENTIAL or RESTRICTED; "financial"
     * = tagged with the FINANCIAL data category.
     */
    @Query(value = """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')) AS sensitive,
                   COUNT(*) FILTER (WHERE sensitivity = 'RESTRICTED') AS restricted,
                   COUNT(*) FILTER (WHERE sensitivity = 'CONFIDENTIAL') AS confidential,
                   COUNT(*) FILTER (WHERE data_categories IS NOT NULL
                                      AND jsonb_exists(data_categories, 'FINANCIAL')) AS financial,
                   COUNT(*) FILTER (WHERE injection_detected = true) AS injections,
                   COUNT(*) FILTER (WHERE root_principal_kind = 'HUMAN') AS human_attributed,
                   MIN(classified_at) AS first_at,
                   MAX(classified_at) AS last_at
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant
            """, nativeQuery = true)
    List<Object[]> dataClassStats(@Param("tenant") String tenant);

    /** Distinct data categories observed across classified responses (single Long). */
    @Query(value = """
            SELECT COUNT(DISTINCT e)
            FROM ws_agentic_security.gateway_response_classification c,
                 jsonb_array_elements_text(c.data_categories) e
            WHERE c.ws_tenant_name = :tenant AND c.data_categories IS NOT NULL
            """, nativeQuery = true)
    Long distinctCategoryCount(@Param("tenant") String tenant);

    /** Enabled custom classification rules in force for this tenant (single Long) — detection-control coverage. */
    @Query(value = """
            SELECT COUNT(*)
            FROM ws_agentic_security.data_tag_rule
            WHERE ws_tenant_name = :tenant AND enabled = true AND rule_type = 'CUSTOM'
            """, nativeQuery = true)
    Long activeCustomRuleCount(@Param("tenant") String tenant);
}
