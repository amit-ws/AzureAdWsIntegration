package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.entity.A2aAgentEntity;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.repository.A2aAgentRepository;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound.A2aAgentDirectory;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Ingests downstream A2A agents into the gateway. Two entry points serve the two sides of the AgentSource
 * seam:
 * <ul>
 *   <li>{@link #ingest} — the admin path: fetch the card, register skills + endpoint, <em>and persist</em>
 *       the agent so the gateway remembers it. This is the write side of the self-describe source.</li>
 *   <li>{@link #activate} — the reconcile path an {@code AgentSource} drives at startup: register skills +
 *       endpoint from the agent's <em>current</em> card <em>without persisting</em>, because the source (the
 *       DB today, a platform control plane later) is authoritative for the inventory.</li>
 * </ul>
 *
 * <p>Registering an agent fetches its Agent Card, registers its skills as {@code SKILL} capabilities
 * ({@link A2aCapabilityRegistrar}) and its endpoint in the {@link A2aAgentDirectory}. Startup reconciliation
 * is owned by {@code AgentSourceReconciler}, which iterates every {@code AgentSource} and calls
 * {@link #activate} — so the capability registry is rebuilt from each agent's current card, and a new source
 * (e.g. platform-sync) snaps in without touching this service.
 */
@Service
@Slf4j
public class A2aAgentIngestionService {

    private final A2aAgentDirectory directory;
    private final A2aCapabilityRegistrar registrar;
    private final A2aAgentRepository repository;
    private final AgentRegistryService agentRegistryService;

    public A2aAgentIngestionService(A2aAgentDirectory directory,
                                    A2aCapabilityRegistrar registrar,
                                    A2aAgentRepository repository,
                                    AgentRegistryService agentRegistryService) {
        this.directory = directory;
        this.registrar = registrar;
        this.repository = repository;
        this.agentRegistryService = agentRegistryService;
    }

    /** Ingest (or refresh) a downstream agent: fetch its Agent Card, register its skills + endpoint, persist it. */
    @Transactional
    public IngestResult ingest(String agentName, String baseUrl) {
        if (agentName == null || agentName.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agentName and baseUrl are required");
        }
        AgentCard card = resolveCard(baseUrl);
        directory.register(agentName, baseUrl);
        int skills = registrar.register(agentName, card);

        String tenant = TenantContext.get();
        A2aAgentEntity entity = repository.findByNameAndWsTenantName(agentName, tenant)
                .orElseGet(A2aAgentEntity::new);
        entity.setName(agentName);
        entity.setBaseUrl(baseUrl);
        if (tenant != null && !tenant.isBlank()) {
            entity.setWsTenantName(tenant);
        }
        repository.save(entity);

        // Unified Agent Model (#2): also record the A2A facet on the canonical agent (gateway_agent). The
        // gateway_a2a_agent write above is kept during the transition so the existing A2A admin reads stay
        // green; the canonical row is what the unified dashboard reads (and the only record after Stage 6).
        agentRegistryService.registerA2aEndpoint(agentName, baseUrl);

        log.info("A2A agent ingested: name='{}', card='{}', skills={}", agentName, card.name(), skills);
        return new IngestResult(agentName, card.name(), skills);
    }

    /** Remove a downstream agent: drop its skills, endpoint, and persisted config. */
    @Transactional
    public void remove(String agentName) {
        directory.remove(agentName);
        registrar.deregister(agentName);
        repository.findByNameAndWsTenantName(agentName, TenantContext.get())
                .ifPresent(repository::delete);
        // Drop the A2A facet on the canonical agent (keeps the identity row if it still speaks MCP).
        agentRegistryService.clearA2aEndpoint(agentName);
        log.info("A2A agent removed: '{}'", agentName);
    }

    /** The registered agents for the current tenant (all agents if no tenant is set). */
    public List<A2aAgentEntity> list() {
        String tenant = TenantContext.get();
        return tenant != null && !tenant.isBlank() ? repository.findByWsTenantName(tenant) : repository.findAll();
    }

    /** A single registered agent for the current tenant, by gateway name. */
    public Optional<A2aAgentEntity> find(String agentName) {
        return repository.findByNameAndWsTenantName(agentName, TenantContext.get());
    }

    /**
     * Best-effort fetch of an agent's current Agent Card — empty if the agent is unreachable or the card
     * cannot be parsed. Used by the admin detail/health reads, where a down agent is a reported state,
     * not an error.
     */
    public Optional<AgentCard> tryFetchCard(String baseUrl) {
        try {
            return Optional.of(resolveCard(baseUrl));
        } catch (Exception e) {
            log.debug("A2A card fetch failed for {}: {}", baseUrl, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Activate a downstream agent <em>without persisting</em> it: register its endpoint in the directory and
     * its skills as capabilities from its <em>current</em> Agent Card. This is the reconcile path an
     * {@code AgentSource} drives — the source owns the inventory, so the gateway records nothing of its own.
     * A card that cannot be fetched (agent temporarily down) does not fail activation: the endpoint stays
     * registered so a later call can re-fetch a fresh card.
     */
    public void activate(String agentName, String baseUrl) {
        if (agentName == null || agentName.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agentName and baseUrl are required");
        }
        directory.register(agentName, baseUrl);
        try {
            AgentCard card = resolveCard(baseUrl);
            int skills = registrar.register(agentName, card);
            log.info("A2A agent activated: name='{}', card='{}', skills={}", agentName, card.name(), skills);
        } catch (Exception e) {
            log.warn("A2A activate: could not fetch card for agent '{}' at {} — {} (endpoint kept registered)",
                    agentName, baseUrl, e.getMessage());
        }
    }

    private AgentCard resolveCard(String baseUrl) {
        try {
            return A2ACardResolver.builder().baseUrl(baseUrl).build().getAgentCard();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch Agent Card from " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    /** Outcome of an ingest: the gateway agent name, the card's advertised name, and the skill count. */
    public record IngestResult(String agentName, String cardName, int skillsRegistered) {
    }
}
