package com.ws.wsAgenticSecurityGateway.protocol.a2a.inbound;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the gateway's own A2A {@link AgentCard} — the self-description served at
 * {@code /a2a/.well-known/agent-card.json} so other agents can discover the skills the gateway fronts. Skills
 * are projected from the registered {@code SKILL} capabilities, so the card always reflects exactly what the
 * gateway governs. (Ingesting a <em>downstream</em> agent's card into the registry is Band 5.)
 */
@Service
public class A2aAgentCardService {

    private final CapabilityRegistryService registryService;

    @Value("${ws.a2a.card.name:WhiteSwan Agentic Gateway}")
    private String cardName;

    @Value("${ws.a2a.card.description:Governed A2A gateway — every agent-to-agent skill invocation is authenticated, delegated (OBO), policy-checked, and audited.}")
    private String cardDescription;

    @Value("${ws.a2a.card.version:1.0.0}")
    private String cardVersion;

    @Value("${ws.a2a.card.url:http://localhost:9492/a2a}")
    private String cardUrl;

    public A2aAgentCardService(CapabilityRegistryService registryService) {
        this.registryService = registryService;
    }

    /** The gateway's Agent Card, with one A2A skill per registered {@code SKILL} capability. */
    public AgentCard buildCard() {
        List<AgentSkill> skills = registryService.getByType(CapabilityDescriptor.CapabilityType.SKILL).stream()
                .map(this::toSkill)
                .toList();

        AgentCapabilities capabilities = AgentCapabilities.builder()
                .streaming(false)
                .pushNotifications(false)
                .build();

        return AgentCard.builder()
                .name(cardName)
                .description(cardDescription)
                .version(cardVersion)
                .url(cardUrl)
                .preferredTransport("JSONRPC")
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", cardUrl)))
                .capabilities(capabilities)
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(skills)
                .build();
    }

    /**
     * The single registered skill's public name when exactly one exists — the fallback target for a message
     * that does not name a skill. {@code null} when zero or many are registered (then the message must name
     * the target in {@code metadata.skillId}).
     */
    public String singleSkillOrNull() {
        List<CapabilityDescriptor> skills = registryService.getByType(CapabilityDescriptor.CapabilityType.SKILL);
        return skills.size() == 1 ? skills.get(0).getPublicName() : null;
    }

    private AgentSkill toSkill(CapabilityDescriptor descriptor) {
        return AgentSkill.builder()
                .id(descriptor.getPublicName())
                .name(descriptor.getPublicName())
                .description(descriptor.getDescription() != null ? descriptor.getDescription() : "")
                .tags(List.of("gateway"))
                .build();
    }
}
