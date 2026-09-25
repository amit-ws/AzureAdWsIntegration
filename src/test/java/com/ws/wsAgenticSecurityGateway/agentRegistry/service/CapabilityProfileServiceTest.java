package com.ws.wsAgenticSecurityGateway.agentRegistry.service;

import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileAssignmentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.AgentCapabilityProfileRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.model.CapabilityDescriptor.CapabilityType;
import com.ws.wsAgenticSecurityGateway.capabilityRegistry.service.CapabilityRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins the gateway's single authoritative choke point for a capability profile rule's type
 * ({@link CapabilityProfileService#normalizeRuleType}). The motivating defect: an A2A agent's SKILL granted to
 * another agent was persisted as TOOL (by the LLM profile assistant / any caller), and the enforcement filter —
 * which narrows a source's capabilities to the rule's type before matching names — then dropped the skill,
 * silently un-granting it. These tests lock in that a named capability's real kind (from the registry) wins.
 */
class CapabilityProfileServiceTest {

    private CapabilityRegistryService registryService;
    private CapabilityProfileService service;

    @BeforeEach
    void setUp() {
        registryService = Mockito.mock(CapabilityRegistryService.class);
        service = new CapabilityProfileService(
                Mockito.mock(AgentCapabilityProfileRepository.class),
                Mockito.mock(AgentCapabilityProfileAssignmentRepository.class),
                Mockito.mock(AgentCapabilityFilterService.class),
                registryService,
                Mockito.mock(GatewayAgentRepository.class),
                Mockito.mock(GatewayAuditService.class),
                Mockito.mock(ApplicationEventPublisher.class));
    }

    private static CapabilityDescriptor cap(String server, String name, CapabilityType type) {
        return CapabilityDescriptor.builder()
                .publicName(server + "." + name)
                .originalName(name)
                .serverConfigName(server)
                .type(type)
                .build();
    }

    @Test
    void skillNamedUnderToolRule_isCorrectedToSkill() {
        when(registryService.getCapabilitiesByServer("fundamentals"))
                .thenReturn(List.of(cap("fundamentals", "earnings", CapabilityType.SKILL)));

        String result = service.normalizeRuleType("fundamentals", "INCLUDE_ONLY", "TOOL", "earnings");

        assertThat(result).isEqualTo("SKILL");
    }

    @Test
    void toolNamedUnderToolRule_staysTool() {
        when(registryService.getCapabilitiesByServer("alphavantage"))
                .thenReturn(List.of(
                        cap("alphavantage", "BALANCE_SHEET", CapabilityType.TOOL),
                        cap("alphavantage", "EARNINGS", CapabilityType.TOOL)));

        String result = service.normalizeRuleType("alphavantage", "INCLUDE_ONLY", "TOOL", "BALANCE_SHEET,EARNINGS");

        assertThat(result).isEqualTo("TOOL");
    }

    @Test
    void allType_isNeverRewritten() {
        // ALL + named already includes the named capabilities regardless of kind; the registry is not consulted.
        String result = service.normalizeRuleType("fundamentals", "INCLUDE_ONLY", "ALL", "earnings");

        assertThat(result).isEqualTo("ALL");
        Mockito.verifyNoInteractions(registryService);
    }

    @Test
    void excludeMode_isLeftAsAuthored() {
        // EXCLUDE ("all except …") is intentionally not re-typed — re-typing could broaden/narrow the exclusion.
        String result = service.normalizeRuleType("fundamentals", "EXCLUDE", "TOOL", "earnings");

        assertThat(result).isEqualTo("TOOL");
        Mockito.verifyNoInteractions(registryService);
    }

    @Test
    void unresolvableName_keepsDeclaredType() {
        // Source/agent not currently registered — nothing to validate against, so do not guess.
        when(registryService.getCapabilitiesByServer("ghost")).thenReturn(List.of());

        String result = service.normalizeRuleType("ghost", "INCLUDE_ONLY", "TOOL", "earnings");

        assertThat(result).isEqualTo("TOOL");
    }

    @Test
    void namesSpanningMultipleKinds_widenToAll() {
        when(registryService.getCapabilitiesByServer("hybrid"))
                .thenReturn(List.of(
                        cap("hybrid", "quote", CapabilityType.SKILL),
                        cap("hybrid", "lookup", CapabilityType.TOOL)));

        String result = service.normalizeRuleType("hybrid", "INCLUDE_ONLY", "TOOL", "quote,lookup");

        assertThat(result).isEqualTo("ALL");
    }
}
