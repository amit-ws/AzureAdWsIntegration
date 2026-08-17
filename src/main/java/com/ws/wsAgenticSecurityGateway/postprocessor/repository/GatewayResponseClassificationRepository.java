package com.ws.wsAgenticSecurityGateway.postprocessor.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store for the post-processor's egress classifications. The classifier writes here (async) as responses flow;
 * the sensitivity overlay, per-tool/agent fingerprints, and the CISO Track-2 views read from here.
 */
@Repository
public interface GatewayResponseClassificationRepository
        extends JpaRepository<GatewayResponseClassificationEntity, UUID> {

    /** Idempotency guard — one classification per source response event. */
    Optional<GatewayResponseClassificationEntity> findBySourceEventId(UUID sourceEventId);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndCorrelationId(String wsTenantName, String correlationId);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndTraceId(String wsTenantName, String traceId);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndProducer(String wsTenantName, String producer);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndSensitivity(String wsTenantName, String sensitivity);

    /** The most-recent classifications for a tenant (page-limited) — the Processed-Data view's default list. */
    List<GatewayResponseClassificationEntity> findByWsTenantNameOrderByClassifiedAtDesc(String wsTenantName, Pageable page);

    // ── Summary aggregates (SQL-side, so counts are accurate rather than derived from a capped list) ──

    long countByWsTenantName(String wsTenantName);

    long countByWsTenantNameAndInjectionDetectedTrue(String wsTenantName);

    @Query("select c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant group by c.sensitivity")
    List<Object[]> sensitivityBreakdown(@Param("tenant") String tenant);
}
