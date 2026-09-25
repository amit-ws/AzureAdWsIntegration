package com.ws.wsAgenticSecurityGateway.protocol.a2a.source;

import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.service.AgentRegistryService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The default {@link AgentSource}: the A2A agents an admin ingested through the gateway's own API. After the
 * Unified Agent Model (#2) these live on the one canonical agent ({@code gateway_agent} rows flagged
 * {@code speaks_a2a}), so this reads them from {@link AgentRegistryService} — there is no separate A2A table.
 * Each agent describes itself via its Agent Card, so {@code AgentSourceReconciler} re-activates each on startup
 * from its <em>current</em> card. Tenant-agnostic at reconcile time (endpoints + skills are keyed by name).
 */
@Component
public class SelfDescribeAgentSource implements AgentSource {

    private final AgentRegistryService agentRegistryService;

    public SelfDescribeAgentSource(AgentRegistryService agentRegistryService) {
        this.agentRegistryService = agentRegistryService;
    }

    @Override
    public String name() {
        return "self-describe";
    }

    @Override
    public List<DiscoveredAgent> discover() {
        return agentRegistryService.getAllA2aEndpoints().stream()
                .map(this::toDiscovered)
                .toList();
    }

    private DiscoveredAgent toDiscovered(GatewayAgentEntity agent) {
        return new DiscoveredAgent(agent.getAgentName(), agent.getA2aBaseUrl(), name());
    }
}
