package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayAgentEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.entity.GatewayHumanUserEntity;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayAgentRepository;
import com.ws.wsAgenticSecurityGateway.agentRegistry.repository.GatewayHumanUserRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AccountabilityReport;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentAccountability;
import com.ws.wsAgenticSecurityGateway.ciso.dto.HumanAccountability;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Accountability from the OBO actChain: chain depth drives DIRECT (human → agent) vs DELEGATED (human → … →
 * agent), the root human is enriched from the registry, the reverse per-human view splits front-door vs
 * downstream agents, and unrooted (no-human) calls are excluded from answerable totals. Policy ownership is the
 * real created_by author. Pins the whole derivation.
 */
class AccountabilityServiceTest {

    private static final String HUMAN = "{\"id\":\"h-1\",\"type\":\"human\",\"username\":\"amit\",\"verified\":true}";
    private static final String CONSOLE = "{\"id\":\"agent-console\",\"type\":\"agent\",\"workload_id\":\"agent-console\",\"verified\":true}";
    private static final String ADVISOR = "{\"id\":\"advisor\",\"type\":\"agent\",\"workload_id\":\"advisor\",\"verified\":true}";
    private static final String MARKET = "{\"id\":\"market-data\",\"type\":\"agent\",\"workload_id\":\"market-data\",\"verified\":true}";
    private static final String LONER = "{\"id\":\"loner\",\"type\":\"agent\",\"workload_id\":\"loner\",\"verified\":true}";
    // The unknownRoot sentinel: type=human but NOT verified — must NOT count as an answerable human.
    private static final String UNKNOWN_HUMAN = "{\"id\":\"unknown\",\"type\":\"human\",\"verified\":false}";

    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final GatewayAgentRepository agentRepo = mock(GatewayAgentRepository.class);
    private final GatewayHumanUserRepository humanRepo = mock(GatewayHumanUserRepository.class);
    private final GatewayPolicyRepository policyRepo = mock(GatewayPolicyRepository.class);

    private final AccountabilityService service = new AccountabilityService(
            pdpRepo, agentRepo, humanRepo, policyRepo, new ObjectMapper());

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        when(humanRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                GatewayHumanUserEntity.builder().idpSubject("h-1").preferredUsername("amit")
                        .fullName("Amit Prakash").email("amit@ws.com").status("ACTIVE").build()));
        when(agentRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                GatewayAgentEntity.builder().agentName("agent-console").approvalStatus("APPROVED").build(),
                GatewayAgentEntity.builder().agentName("advisor").approvalStatus("APPROVED").build(),
                GatewayAgentEntity.builder().agentName("market-data").approvalStatus("PENDING").build()));
        when(policyRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                GatewayPolicyEntity.builder().createdBy("admin").build(),
                GatewayPolicyEntity.builder().createdBy("admin").build(),
                GatewayPolicyEntity.builder().createdBy("system").build(),
                GatewayPolicyEntity.builder().createdBy("").build()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Object[] row(String agent, String chainJson) {
        return new Object[]{ Timestamp.valueOf(LocalDateTime.of(2026, 8, 13, 10, 0)),
                agent, "resource", "action", "ALLOW", "corr", chainJson };
    }

    @Test
    void report_splitsDirectVsDelegated_reverseHuman_andPolicyOwners() {
        when(pdpRepo.accountabilityEvaluations(eq("acme"), anyInt())).thenReturn(List.of(
                row("agent-console", "[" + HUMAN + "," + CONSOLE + "]"),              // DIRECT
                row("agent-console", "[" + HUMAN + "," + CONSOLE + "]"),              // DIRECT
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]"),    // DELEGATED (depth 3)
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]"),
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]"),
                row("market-data", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "," + MARKET + "]"), // DELEGATED (depth 4)
                row("loner", "[" + LONER + "]"),                                      // UNROOTED (agent root, no human)
                row("ghost", "[" + UNKNOWN_HUMAN + "]")));                            // UNROOTED (unverified human root)

        AccountabilityReport r = service.getReport();

        AccountabilityReport.Summary s = r.summary();
        assertThat(s.governedRequests()).isEqualTo(8);
        assertThat(s.directRequests()).isEqualTo(2);
        assertThat(s.delegatedRequests()).isEqualTo(4);
        assertThat(s.unrootedRequests()).isEqualTo(2);          // loner (agent root) + ghost (unverified human)
        assertThat(s.actingAgents()).isEqualTo(5);              // console, advisor, market-data, loner, ghost
        assertThat(s.registeredAgents()).isEqualTo(3);          // roster
        assertThat(s.agentsWithHumanRoot()).isEqualTo(3);
        assertThat(s.agentsWithoutHumanRoot()).isEqualTo(2);    // loner + ghost
        assertThat(s.distinctHumans()).isEqualTo(1);            // amit only — the unknown root is not counted

        AccountabilityReport.AgentRow advisor = r.agents().stream()
                .filter(a -> a.agentName().equals("advisor")).findFirst().orElseThrow();
        assertThat(advisor.delegatedRequests()).isEqualTo(3);
        assertThat(advisor.directRequests()).isEqualTo(0);
        assertThat(advisor.hasVerifiedHumanRoot()).isTrue();
        assertThat(advisor.maxDelegationDepth()).isEqualTo(3);
        assertThat(advisor.primaryHuman()).isEqualTo("amit");

        AccountabilityReport.AgentRow market = r.agents().stream()
                .filter(a -> a.agentName().equals("market-data")).findFirst().orElseThrow();
        assertThat(market.maxDelegationDepth()).isEqualTo(4);

        AccountabilityReport.AgentRow loner = r.agents().stream()
                .filter(a -> a.agentName().equals("loner")).findFirst().orElseThrow();
        assertThat(loner.hasVerifiedHumanRoot()).isFalse();
        assertThat(loner.unrootedRequests()).isEqualTo(1);
        assertThat(loner.approvalStatus()).isNull();            // not in the registry

        // Unverified human root ("unknown" sentinel) is unaccountable, not a direct action.
        AccountabilityReport.AgentRow ghost = r.agents().stream()
                .filter(a -> a.agentName().equals("ghost")).findFirst().orElseThrow();
        assertThat(ghost.hasVerifiedHumanRoot()).isFalse();
        assertThat(ghost.directRequests()).isEqualTo(0);
        assertThat(ghost.unrootedRequests()).isEqualTo(1);

        assertThat(r.humans()).hasSize(1);
        HumanAccountability amit = r.humans().get(0);
        assertThat(amit.username()).isEqualTo("amit");
        assertThat(amit.fullName()).isEqualTo("Amit Prakash");     // enriched from registry
        assertThat(amit.totalRequests()).isEqualTo(6);             // 2 + 3 + 1 (loner excluded — no human root)
        assertThat(amit.directAgents()).isEqualTo(1);              // agent-console
        assertThat(amit.delegatedAgents()).isEqualTo(2);           // advisor, market-data

        assertThat(r.policyOwnership().totalPolicies()).isEqualTo(4);
        assertThat(r.policyOwnership().unowned()).isEqualTo(1);
        assertThat(r.policyOwnership().byOwner()).containsEntry("admin", 2L).containsEntry("system", 1L)
                .containsEntry("unassigned", 1L);
    }

    @Test
    void agentDetail_resolvesDelegatedPathAndPrimaryHuman() {
        when(pdpRepo.accountabilityEvaluationsForAgent(eq("acme"), eq("advisor"), anyInt())).thenReturn(List.of(
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]"),
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]"),
                row("advisor", "[" + HUMAN + "," + CONSOLE + "," + ADVISOR + "]")));

        AgentAccountability a = service.getAgentAccountability("advisor");

        assertThat(a.governedRequests()).isEqualTo(3);
        assertThat(a.delegatedRequests()).isEqualTo(3);
        assertThat(a.directRequests()).isEqualTo(0);
        assertThat(a.unrootedRequests()).isEqualTo(0);
        assertThat(a.maxDelegationDepth()).isEqualTo(3);
        assertThat(a.approvalStatus()).isEqualTo("APPROVED");
        assertThat(a.hasVerifiedHumanRoot()).isTrue();

        assertThat(a.accountableHumans()).hasSize(1);
        AgentAccountability.AccountableHuman primary = a.primaryHuman();
        assertThat(primary.username()).isEqualTo("amit");
        assertThat(primary.email()).isEqualTo("amit@ws.com");
        assertThat(primary.requests()).isEqualTo(3);
        assertThat(primary.delegatedRequests()).isEqualTo(3);

        assertThat(a.delegationPaths()).hasSize(1);
        AgentAccountability.DelegationPath path = a.delegationPaths().get(0);
        assertThat(path.path()).containsExactly("amit", "agent-console", "advisor");
        assertThat(path.attribution()).isEqualTo("DELEGATED");
        assertThat(path.requests()).isEqualTo(3);
    }
}
