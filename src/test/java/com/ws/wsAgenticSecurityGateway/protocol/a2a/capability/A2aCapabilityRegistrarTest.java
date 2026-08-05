package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability;

import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link A2aCapabilityRegistrar}: a downstream agent's Agent Card skills map to namespaced
 * {@code SKILL} capabilities ({@code protocol=A2A}, {@code originalName}=the agent's own skill id). Also
 * exercises {@code AgentCard.build()} with the field set the gateway uses.
 */
class A2aCapabilityRegistrarTest {

    private final CapabilityRegistryService registry = mock(CapabilityRegistryService.class);
    private final GatewayAuditService audit = mock(GatewayAuditService.class);
    private final A2aCapabilityRegistrar registrar = new A2aCapabilityRegistrar(registry, audit);

    @Test
    @SuppressWarnings("unchecked")
    void register_mapsCardSkillsToNamespacedSkillCapabilities() {
        AgentCard card = AgentCard.builder()
                .name("Billing Agent")
                .description("Quotes and refunds")
                .version("1.0.0")
                .url("http://billing:1234/a2a")
                .preferredTransport("JSONRPC")
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://billing:1234/a2a")))
                .capabilities(AgentCapabilities.builder().streaming(false).pushNotifications(false).build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(
                        AgentSkill.builder().id("get_quote").name("Get Quote")
                                .description("quote a price").tags(List.of("billing")).build(),
                        AgentSkill.builder().id("refund").name("Refund")
                                .description("issue a refund").tags(List.of("billing")).build()))
                .build();

        int count = registrar.register("billing", card);

        assertThat(count).isEqualTo(2);

        ArgumentCaptor<List<CapabilityDescriptor>> captor = ArgumentCaptor.forClass(List.class);
        verify(registry).registerServerCapabilities(eq("billing"), captor.capture());
        List<CapabilityDescriptor> descriptors = captor.getValue();

        assertThat(descriptors).extracting(CapabilityDescriptor::getPublicName)
                .containsExactly("billing.get_quote", "billing.refund");
        assertThat(descriptors).extracting(CapabilityDescriptor::getOriginalName)
                .containsExactly("get_quote", "refund");
        assertThat(descriptors).allSatisfy(d -> {
            assertThat(d.getType()).isEqualTo(CapabilityDescriptor.CapabilityType.SKILL);
            assertThat(d.getProtocol()).isEqualTo("A2A");
            assertThat(d.getServerConfigName()).isEqualTo("billing");
        });
    }
}
