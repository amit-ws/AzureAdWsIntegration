package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.entity.A2aAgentEntity;
import com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.repository.A2aAgentRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The default {@link AgentSource}: the agents an admin ingested through the gateway's own API, persisted in
 * {@link A2aAgentRepository}. Each ingested agent describes itself via its Agent Card, so the gateway's DB is
 * authoritative for the inventory here — {@code AgentSourceReconciler} re-activates each on startup from its
 * <em>current</em> card. Tenant-agnostic at reconcile time (like the endpoints and skills it feeds, which are
 * keyed by agent name), matching how startup reload has always worked.
 */
@Component
public class SelfDescribeAgentSource implements AgentSource {

    private final A2aAgentRepository repository;

    public SelfDescribeAgentSource(A2aAgentRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "self-describe";
    }

    @Override
    public List<DiscoveredAgent> discover() {
        return repository.findAll().stream()
                .map(this::toDiscovered)
                .toList();
    }

    private DiscoveredAgent toDiscovered(A2aAgentEntity entity) {
        return new DiscoveredAgent(entity.getName(), entity.getBaseUrl(), name());
    }
}
