package com.ws.wsAgenticSecurityGateway.postprocessor.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
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

    // ── CISO Dashboard windowed counts (for KPI tiles + period-over-period deltas) ──

    long countByWsTenantNameAndClassifiedAtBetween(String wsTenantName, LocalDateTime from, LocalDateTime to);

    long countByWsTenantNameAndSensitivityInAndClassifiedAtBetween(
            String wsTenantName, Collection<String> sensitivities, LocalDateTime from, LocalDateTime to);

    long countByWsTenantNameAndProtocolAndClassifiedAtBetween(
            String wsTenantName, String protocol, LocalDateTime from, LocalDateTime to);

    /**
     * Enforcement coverage for the policy-coverage widget. One row:
     * {@code [sensitiveCaps, coveredSensitiveCaps, totalCaps, coveredCaps, servers, coveredServers]} (all Long).
     * "Covered" = at least one classification for that capability/server carried an egress enforcement policy id;
     * a capability is keyed by {@code type:name}. Honest to our advisory-first reality — uncovered sensitive
     * capabilities are exactly the enforcement gaps.
     */
    @Query(value = """
            SELECT
              COUNT(DISTINCT COALESCE(capability_type,'') || ':' || COALESCE(capability_name,''))
                  FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')) AS sensitive_caps,
              COUNT(DISTINCT COALESCE(capability_type,'') || ':' || COALESCE(capability_name,''))
                  FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED') AND egress_policy_id IS NOT NULL) AS covered_sensitive_caps,
              COUNT(DISTINCT COALESCE(capability_type,'') || ':' || COALESCE(capability_name,'')) AS total_caps,
              COUNT(DISTINCT COALESCE(capability_type,'') || ':' || COALESCE(capability_name,''))
                  FILTER (WHERE egress_policy_id IS NOT NULL) AS covered_caps,
              COUNT(DISTINCT producer_server_id) FILTER (WHERE producer_server_id IS NOT NULL) AS servers,
              COUNT(DISTINCT producer_server_id)
                  FILTER (WHERE producer_server_id IS NOT NULL AND egress_policy_id IS NOT NULL) AS covered_servers
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant
            """, nativeQuery = true)
    List<Object[]> enforcementCoverage(@Param("tenant") String tenant);

    /**
     * Sensitive capabilities that reached a consumer with NO egress enforcement policy — the priority-actions
     * "restricted data, no policy" detection. Row shape (ranked, most-sensitive first):
     * {@code [capability_type, capability_name, producer, producer_server_id, peak_rank(int),
     * exposures(Long), distinct_roots(Long), distinct_agents(Long), last_at(Timestamp)]}.
     */
    @Query(value = """
            SELECT capability_type, capability_name, producer, producer_server_id,
                   MAX(CASE sensitivity WHEN 'RESTRICTED' THEN 3 WHEN 'CONFIDENTIAL' THEN 2
                                        WHEN 'INTERNAL' THEN 1 ELSE 0 END) AS peak_rank,
                   COUNT(*) AS exposures,
                   COUNT(DISTINCT root_principal_id) AS roots,
                   COUNT(DISTINCT consumer_agent_id) AS agents,
                   MAX(classified_at) AS last_at
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant
              AND sensitivity IN ('CONFIDENTIAL','RESTRICTED')
              AND egress_policy_id IS NULL
            GROUP BY capability_type, capability_name, producer, producer_server_id
            ORDER BY peak_rank DESC, exposures DESC
            """, nativeQuery = true)
    List<Object[]> sensitiveExposures(@Param("tenant") String tenant);

    /**
     * Agents (consumers) that received data from many distinct servers in the window — the "broad fan-out"
     * detection. Row shape: {@code [consumer, consumer_agent_id, servers(Long), calls(Long), last_at(Timestamp)]},
     * only agents at/over {@code minServers} distinct servers, widest first.
     */
    @Query(value = """
            SELECT consumer, consumer_agent_id,
                   COUNT(DISTINCT producer_server_id) AS servers,
                   COUNT(*) AS calls,
                   MAX(classified_at) AS last_at
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant AND producer_kind = 'SERVER' AND consumer IS NOT NULL
            GROUP BY consumer, consumer_agent_id
            HAVING COUNT(DISTINCT producer_server_id) >= :minServers
            ORDER BY servers DESC
            """, nativeQuery = true)
    List<Object[]> agentServerFanout(@Param("tenant") String tenant, @Param("minServers") int minServers);

    /**
     * Time-bucketed classification traffic for the traffic chart (#70). {@code unit} is a date_trunc granularity
     * ("hour" / "day"). Row shape (oldest first): {@code [bucket(Timestamp), total, sensitive, mcp, a2a]} (Long counts).
     */
    @Query(value = """
            SELECT date_trunc(cast(:unit as text), classified_at) AS bucket,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')) AS sensitive,
                   COUNT(*) FILTER (WHERE protocol = 'MCP') AS mcp,
                   COUNT(*) FILTER (WHERE protocol = 'A2A') AS a2a
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant AND classified_at >= :from
            GROUP BY 1
            ORDER BY 1
            """, nativeQuery = true)
    List<Object[]> trafficBuckets(@Param("tenant") String tenant, @Param("unit") String unit,
                                  @Param("from") LocalDateTime from);

    /**
     * Per-agent (consumer) risk rollup for the hotspots widget (#72). Row shape:
     * {@code [consumer(name), consumer_agent_id, peak_rank(int), sensitive(Long), total(Long),
     * servers(Long), last_at(Timestamp)]}.
     */
    @Query(value = """
            SELECT consumer, consumer_agent_id,
                   MAX(CASE sensitivity WHEN 'RESTRICTED' THEN 3 WHEN 'CONFIDENTIAL' THEN 2
                                        WHEN 'INTERNAL' THEN 1 ELSE 0 END) AS peak_rank,
                   COUNT(*) FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')) AS sensitive,
                   COUNT(*) AS total,
                   COUNT(DISTINCT producer_server_id) AS servers,
                   MAX(classified_at) AS last_at
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant AND consumer IS NOT NULL
            GROUP BY consumer, consumer_agent_id
            """, nativeQuery = true)
    List<Object[]> consumerAgentRisk(@Param("tenant") String tenant);

    /**
     * Per-server risk rollup for the hotspots widget (#72). Row shape:
     * {@code [producer_server_id, producer(name), peak_rank(int), sensitive(Long), total(Long),
     * uncovered_sensitive(Long), last_at(Timestamp)]} — {@code uncovered_sensitive} = sensitive responses this
     * server returned with no egress enforcement policy (the "no block policy" reason).
     */
    @Query(value = """
            SELECT producer_server_id, producer,
                   MAX(CASE sensitivity WHEN 'RESTRICTED' THEN 3 WHEN 'CONFIDENTIAL' THEN 2
                                        WHEN 'INTERNAL' THEN 1 ELSE 0 END) AS peak_rank,
                   COUNT(*) FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED')) AS sensitive,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE sensitivity IN ('CONFIDENTIAL','RESTRICTED') AND egress_policy_id IS NULL) AS uncovered,
                   MAX(classified_at) AS last_at
            FROM ws_agentic_security.gateway_response_classification
            WHERE ws_tenant_name = :tenant AND producer_kind = 'SERVER' AND producer_server_id IS NOT NULL
            GROUP BY producer_server_id, producer
            """, nativeQuery = true)
    List<Object[]> serverRisk(@Param("tenant") String tenant);

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
