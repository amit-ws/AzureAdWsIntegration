package com.ws.wsAgenticSecurityGateway.capabilityRegistry.service;

import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The neutral capability-index contract, exercised with no protocol involved — this is exactly what a second
 * protocol's registrar (A2A) will feed. Pins the replace-per-server semantics of
 * {@link CapabilityRegistryService#registerServerCapabilities} and {@link CapabilityRegistryService#evictServer}.
 */
class CapabilityRegistryServiceTest {

    private static CapabilityDescriptor tool(String server, String name) {
        return CapabilityDescriptor.builder()
                .publicName(server + "_" + name)
                .originalName(name)
                .serverConfigName(server)
                .type(CapabilityType.TOOL)
                .build();
    }

    @Test
    void registerExposesCapabilitiesGroupedByServer() {
        CapabilityRegistryService registry = new CapabilityRegistryService();

        registry.registerServerCapabilities("s", List.of(tool("s", "a"), tool("s", "b")));

        assertThat(registry.exists("s_a")).isTrue();
        assertThat(registry.exists("s_b")).isTrue();
        assertThat(registry.getCapabilitiesByServer("s"))
                .extracting(CapabilityDescriptor::getPublicName)
                .containsExactlyInAnyOrder("s_a", "s_b");
        assertThat(registry.getTotalCapabilityCount()).isEqualTo(2);
        assertThat(registry.getRegisteredServerNames()).containsExactly("s");
    }

    @Test
    void reRegisterReplacesTheServersPreviousSet() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.registerServerCapabilities("s", List.of(tool("s", "a"), tool("s", "b")));

        registry.registerServerCapabilities("s", List.of(tool("s", "a")));

        assertThat(registry.exists("s_a")).isTrue();
        assertThat(registry.exists("s_b")).isFalse();   // replaced, not merged
        assertThat(registry.getTotalCapabilityCount()).isEqualTo(1);
    }

    @Test
    void getByTypeFiltersToTheRequestedKind() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        CapabilityDescriptor prompt = CapabilityDescriptor.builder()
                .publicName("s_p").originalName("p").serverConfigName("s")
                .type(CapabilityType.PROMPT).build();
        registry.registerServerCapabilities("s", List.of(tool("s", "a"), tool("s", "b"), prompt));

        assertThat(registry.getByType(CapabilityType.TOOL))
                .extracting(CapabilityDescriptor::getPublicName)
                .containsExactlyInAnyOrder("s_a", "s_b");
        assertThat(registry.getByType(CapabilityType.PROMPT))
                .extracting(CapabilityDescriptor::getPublicName)
                .containsExactly("s_p");
        assertThat(registry.getByType(CapabilityType.RESOURCE)).isEmpty();
        // the convenience aliases delegate to getByType
        assertThat(registry.getToolDescriptors()).hasSize(2);
        assertThat(registry.getPromptDescriptors()).hasSize(1);
    }

    @Test
    void registeringOneServerDoesNotDisturbAnother() {
        CapabilityRegistryService registry = new CapabilityRegistryService();
        registry.registerServerCapabilities("s1", List.of(tool("s1", "a")));
        registry.registerServerCapabilities("s2", List.of(tool("s2", "b")));

        registry.evictServer("s1");

        assertThat(registry.exists("s1_a")).isFalse();
        assertThat(registry.exists("s2_b")).isTrue();
        assertThat(registry.getRegisteredServerNames()).containsExactly("s2");
    }
}
