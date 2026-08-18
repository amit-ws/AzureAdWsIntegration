package com.ws.wsAgenticSecurityGateway.postprocessor.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    // ── Insights aggregates (Step 4: fingerprints / sharing / drift) ──

    /** Per-capability sensitivity profile: [producer, capabilityName, capabilityType, protocol, sensitivity, count, lastSeen]. */
    @Query("select c.producer, c.capabilityName, c.capabilityType, c.protocol, c.sensitivity, count(c), max(c.classifiedAt) "
            + "from GatewayResponseClassificationEntity c where c.wsTenantName = :tenant "
            + "group by c.producer, c.capabilityName, c.capabilityType, c.protocol, c.sensitivity")
    List<Object[]> capabilityProfileRows(@Param("tenant") String tenant);

    /** Per producer→consumer edge sensitivity profile: [producer, consumer, sensitivity, count, lastSeen]. */
    @Query("select c.producer, c.consumer, c.sensitivity, count(c), max(c.classifiedAt) "
            + "from GatewayResponseClassificationEntity c where c.wsTenantName = :tenant "
            + "group by c.producer, c.consumer, c.sensitivity")
    List<Object[]> sharingEdgeRows(@Param("tenant") String tenant);

    /**
     * Drift buckets: [producer, capabilityName, sensitivity, recent(boolean), count] split at {@code cutoff}.
     * Groups by the 4th output column (the {@code recent} expression) rather than repeating {@code (classified_at
     * >= :cutoff)} — Hibernate binds the named param twice as two positional params, which Postgres then treats as
     * different expressions and rejects; the ordinal keeps {@code classified_at} in a single grouped expression.
     */
    @Query(value = "select producer, capability_name, sensitivity, (classified_at >= :cutoff) as recent, count(*) "
            + "from ws_agentic_security.gateway_response_classification where ws_tenant_name = :tenant "
            + "group by producer, capability_name, sensitivity, 4", nativeQuery = true)
    List<Object[]> driftRows(@Param("tenant") String tenant, @Param("cutoff") LocalDateTime cutoff);

    // ── Entity-sensitivity rollups (per agent / server / tool, keyed by exact id) ──

    /** [consumerAgentId, sensitivity, count] — an agent as the receiver of data. */
    @Query("select c.consumerAgentId, c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.consumerAgentId is not null group by c.consumerAgentId, c.sensitivity")
    List<Object[]> consumerAgentSensitivity(@Param("tenant") String tenant);

    /** [producerAgentId, sensitivity, count] — an agent as the producer (its skills). */
    @Query("select c.producerAgentId, c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.producerAgentId is not null group by c.producerAgentId, c.sensitivity")
    List<Object[]> producerAgentSensitivity(@Param("tenant") String tenant);

    /** [producerServerId, producer(name), sensitivity, count] — a server (tool/prompt/resource producer). */
    @Query("select c.producerServerId, c.producer, c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.producerKind = 'SERVER' group by c.producerServerId, c.producer, c.sensitivity")
    List<Object[]> serverSensitivity(@Param("tenant") String tenant);

    /** [producerServerId, producer(name), capabilityType, capabilityName, sensitivity, count] — every SERVER
     *  capability (tool / prompt / resource), grouped by type + name. */
    @Query("select c.producerServerId, c.producer, c.capabilityType, c.capabilityName, c.sensitivity, count(c) "
            + "from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.producerKind = 'SERVER' "
            + "group by c.producerServerId, c.producer, c.capabilityType, c.capabilityName, c.sensitivity")
    List<Object[]> serverCapabilitySensitivity(@Param("tenant") String tenant);

    /** [producerAgentId, producer(name), capabilityName, sensitivity, count] — per skill (agent id + skill name). */
    @Query("select c.producerAgentId, c.producer, c.capabilityName, c.sensitivity, count(c) "
            + "from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.producerKind = 'AGENT' and c.producerAgentId is not null "
            + "group by c.producerAgentId, c.producer, c.capabilityName, c.sensitivity")
    List<Object[]> skillSensitivity(@Param("tenant") String tenant);

    // ── Root-principal (human / NHI) rollups — who, at the root of the OBO chain, triggered sensitive data ──

    /** [rootPrincipalName (username), sensitivity, count] — a HUMAN root's sensitivity footprint. */
    @Query("select c.rootPrincipalName, c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.rootPrincipalKind = 'HUMAN' and c.rootPrincipalName is not null "
            + "group by c.rootPrincipalName, c.sensitivity")
    List<Object[]> humanRootSensitivity(@Param("tenant") String tenant);

    /** [rootPrincipalId (nhi UUID), sensitivity, count] — an NHI/service-account root's sensitivity footprint. */
    @Query("select c.rootPrincipalId, c.sensitivity, count(c) from GatewayResponseClassificationEntity c "
            + "where c.wsTenantName = :tenant and c.rootPrincipalKind = 'NHI' and c.rootPrincipalId is not null "
            + "group by c.rootPrincipalId, c.sensitivity")
    List<Object[]> nhiRootSensitivity(@Param("tenant") String tenant);

    // ── Advanced filtering (Processed-Data multi-select facets) ──

    /**
     * Multi-facet filtered classifications, newest first. Each facet is an optional {@code chr(10)}-joined value
     * list (null = "don't filter on this facet"); within a facet the match is OR (any value), across facets it is
     * AND. Passing a single joined string per facet — rather than a collection — sidesteps both the empty-{@code IN}
     * error and jsonb's bare {@code ?} operator: scalar facets use {@code = any(string_to_array(...))}, and the
     * category facet (a jsonb array column) matches via {@code jsonb_array_elements_text} so a row counts if ANY of
     * its categories is selected.
     */
    @Query(value = "select c.* from ws_agentic_security.gateway_response_classification c "
            + "where c.ws_tenant_name = :tenant "
            + "and (cast(:sensitivities as text) is null or c.sensitivity = any(string_to_array(cast(:sensitivities as text), chr(10)))) "
            + "and (cast(:capabilityTypes as text) is null or c.capability_type = any(string_to_array(cast(:capabilityTypes as text), chr(10)))) "
            + "and (cast(:protocols as text) is null or c.protocol = any(string_to_array(cast(:protocols as text), chr(10)))) "
            + "and (cast(:producers as text) is null or c.producer = any(string_to_array(cast(:producers as text), chr(10)))) "
            + "and (cast(:categories as text) is null or exists ("
            + "     select 1 from jsonb_array_elements_text(c.data_categories) e "
            + "     where e = any(string_to_array(cast(:categories as text), chr(10))))) "
            + "order by c.classified_at desc nulls last limit :limit", nativeQuery = true)
    List<GatewayResponseClassificationEntity> search(@Param("tenant") String tenant,
                                                     @Param("sensitivities") String sensitivities,
                                                     @Param("capabilityTypes") String capabilityTypes,
                                                     @Param("protocols") String protocols,
                                                     @Param("producers") String producers,
                                                     @Param("categories") String categories,
                                                     @Param("limit") int limit);

    // ── Distinct facet values (the filter dropdowns' choices, served from the backend) ──

    @Query(value = "select distinct sensitivity from ws_agentic_security.gateway_response_classification "
            + "where ws_tenant_name = :tenant and sensitivity is not null order by 1", nativeQuery = true)
    List<String> distinctSensitivities(@Param("tenant") String tenant);

    @Query(value = "select distinct capability_type from ws_agentic_security.gateway_response_classification "
            + "where ws_tenant_name = :tenant and capability_type is not null order by 1", nativeQuery = true)
    List<String> distinctCapabilityTypes(@Param("tenant") String tenant);

    @Query(value = "select distinct protocol from ws_agentic_security.gateway_response_classification "
            + "where ws_tenant_name = :tenant and protocol is not null order by 1", nativeQuery = true)
    List<String> distinctProtocols(@Param("tenant") String tenant);

    @Query(value = "select distinct producer from ws_agentic_security.gateway_response_classification "
            + "where ws_tenant_name = :tenant and producer is not null order by 1", nativeQuery = true)
    List<String> distinctProducers(@Param("tenant") String tenant);

    @Query(value = "select distinct e from ws_agentic_security.gateway_response_classification c, "
            + "jsonb_array_elements_text(c.data_categories) e "
            + "where c.ws_tenant_name = :tenant and c.data_categories is not null order by 1", nativeQuery = true)
    List<String> distinctCategories(@Param("tenant") String tenant);
}
