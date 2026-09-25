package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability;

import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.outbound.A2aAgentDirectory;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.client.http.A2ACardResolver;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ingests downstream A2A agents into the gateway. After the Unified Agent Model (#2) an A2A agent IS a
 * canonical {@code gateway_agent} (flagged {@code speaks_a2a} with its base URL) — there is no separate A2A
 * table — so ingestion registers the agent's skills + endpoint and records the A2A facet on the canonical
 * agent via {@link AgentRegistryService}.
 *
 * <p>Two entry points serve the {@code AgentSource} seam:
 * <ul>
 *   <li>{@link #ingest} — the admin path: fetch the card, register skills + endpoint, and record the canonical
 *       A2A facet so the gateway remembers it.</li>
 *   <li>{@link #activate} — the reconcile path an {@code AgentSource} drives at startup: register skills +
 *       endpoint from the agent's <em>current</em> card without persisting (the source owns the inventory).</li>
 * </ul>
 */
@Service
@Slf4j
public class A2aAgentIngestionService {

    private final A2aAgentDirectory directory;
    private final A2aCapabilityRegistrar registrar;
    private final AgentRegistryService agentRegistryService;
    private final GatewayAuditService auditService;

    public A2aAgentIngestionService(A2aAgentDirectory directory,
                                    A2aCapabilityRegistrar registrar,
                                    AgentRegistryService agentRegistryService,
                                    GatewayAuditService auditService) {
        this.directory = directory;
        this.registrar = registrar;
        this.agentRegistryService = agentRegistryService;
        this.auditService = auditService;
    }

    /** Ingest (or refresh) a downstream agent: fetch its Agent Card, register its skills + endpoint, record it. */
    @Transactional
    public IngestResult ingest(String agentName, String baseUrl) {
        if (agentName == null || agentName.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agentName and baseUrl are required");
        }
        return register(agentName, baseUrl, resolveCard(baseUrl));
    }

    /**
     * Register a downstream agent from an Agent Card supplied directly (admin JSON upload) rather than fetched
     * from the agent's URL — the card's skills are registered as-is. Used by the bulk/single import endpoint.
     */
    @Transactional
    public IngestResult ingestFromCard(String agentName, String baseUrl, AgentCard card) {
        if (agentName == null || agentName.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agentName and baseUrl are required");
        }
        if (card == null) {
            throw new IllegalArgumentException("agent card is required");
        }
        return register(agentName, baseUrl, card);
    }

    /**
     * Shared registration path: reject a base URL already registered to a <em>different</em> agent, register the
     * card's skills + the A2A endpoint on the canonical agent, and audit the agent registration (distinct from the
     * per-skill capability events) noting whether the agent was newly onboarded or re-ingested.
     */
    private IngestResult register(String agentName, String baseUrl, AgentCard card) {
        agentRegistryService.getA2aAgentByUrl(baseUrl).ifPresent(existing -> {
            if (!existing.getAgentName().equals(agentName)) {
                throw new IllegalArgumentException("Base URL '" + baseUrl + "' is already registered to agent '"
                        + existing.getAgentName() + "'.");
            }
        });
        boolean newlyRegistered = agentRegistryService.getA2aAgent(agentName).isEmpty();

        directory.register(agentName, baseUrl);
        int skills = registrar.register(agentName, card);
        // Record the A2A facet on the canonical agent (gateway_agent) — the single source of truth (#2).
        agentRegistryService.registerA2aEndpoint(agentName, baseUrl);
        auditService.auditAgentRegistered(agentName, baseUrl, card.name(), skills, newlyRegistered);

        log.info("A2A agent {}: name='{}', card='{}', skills={}, url={}",
                newlyRegistered ? "registered" : "re-ingested", agentName, card.name(), skills, baseUrl);
        return new IngestResult(agentName, card.name(), skills);
    }

    /** Remove a downstream agent: drop its skills, endpoint, and canonical A2A facet. */
    @Transactional
    public void remove(String agentName) {
        directory.remove(agentName);
        registrar.deregister(agentName);
        agentRegistryService.clearA2aEndpoint(agentName);
        log.info("A2A agent removed: '{}'", agentName);
    }

    /**
     * Best-effort fetch of an agent's current Agent Card — empty if the agent is unreachable or the card
     * cannot be parsed. Used by the admin detail/health reads, where a down agent is a reported state.
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
     * {@code AgentSource} drives — the source owns the inventory. A card that cannot be fetched (agent
     * temporarily down) does not fail activation: the endpoint stays registered for a later re-fetch.
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
