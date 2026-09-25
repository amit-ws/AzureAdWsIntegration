package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Registers a downstream A2A agent's skills (from its Agent Card) as {@code SKILL} capabilities in the shared
 * capability registry — the A2A analogue of {@code McpCapabilityRegistrar}.
 *
 * <p>Each skill becomes a {@link CapabilityDescriptor} keyed by a namespaced public name
 * ({@code <agent>.<skillId>}) with {@code protocol="A2A"} and {@code originalName} = the agent's own skill id.
 * So the spine resolves the public name, the generic router dispatches it to the A2A adapter (by protocol),
 * and the adapter forwards the <em>original</em> skill id downstream. Registration replaces any prior skills
 * for the agent, so re-ingesting an updated card is idempotent.
 */
@Service
@Slf4j
public class A2aCapabilityRegistrar {

    private final CapabilityRegistryService registryService;
    private final GatewayAuditService auditService;

    public A2aCapabilityRegistrar(CapabilityRegistryService registryService, GatewayAuditService auditService) {
        this.registryService = registryService;
        this.auditService = auditService;
    }

    /** Register (replacing any prior) the agent's skills; returns the number registered. */
    public int register(String agentName, AgentCard card) {
        List<AgentSkill> skills = card != null && card.skills() != null ? card.skills() : List.of();
        List<CapabilityDescriptor> descriptors = skills.stream()
                .filter(s -> s.id() != null && !s.id().isBlank())
                .map(s -> CapabilityDescriptor.builder()
                        .publicName(publicName(agentName, s.id()))
                        .originalName(s.id())
                        .serverConfigName(agentName)
                        .type(CapabilityDescriptor.CapabilityType.SKILL)
                        .protocol("A2A")
                        .description(firstNonBlank(s.description(), s.name(), ""))
                        .build())
                .toList();

        registryService.registerServerCapabilities(agentName, descriptors);
        descriptors.forEach(d -> auditService.auditRegistryCapabilityRegistered(
                null, agentName, d.getPublicName(), "SKILL"));
        log.info("A2A agent '{}' registered {} skill(s) into the capability registry", agentName, descriptors.size());
        return descriptors.size();
    }

    /** Remove all of an agent's registered skills. */
    public void deregister(String agentName) {
        registryService.evictServer(agentName);
        log.info("A2A agent '{}' capabilities evicted", agentName);
    }

    /** The gateway-facing public name for a downstream skill: {@code <agent>.<skillId>}. */
    public static String publicName(String agentName, String skillId) {
        return agentName + "." + skillId;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
