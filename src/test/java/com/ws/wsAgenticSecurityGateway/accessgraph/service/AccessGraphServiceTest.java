package com.ws.wsAgenticSecurityGateway.accessgraph.service;

import com.ws.wsAgenticSecurityGateway.accessgraph.dto.AccessGraph;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditStatus;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ReachSummary;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentBlastRadius.ResourceReach;
import com.ws.wsAgenticSecurityGateway.ciso.service.BlastRadiusService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Identity Access Graph must merge the two overlays correctly: an observed edge that policy also grants reads
 * as "in balance" (no gap), while a policy grant that was never exercised reads as OVER_PRIVILEGE — the whole
 * point of the feature. This pins that merge so a regression can't silently flatten the graph back to audit-only.
 */
class AccessGraphServiceTest {

    private static final String TENANT = "acme";

    private final GatewayAuditLogRepository auditRepo = mock(GatewayAuditLogRepository.class);
    private final BlastRadiusService blastRadius = mock(BlastRadiusService.class);
    private final GatewayResponseClassificationRepository classificationRepo =
            mock(GatewayResponseClassificationRepository.class);
    private final AccessGraphService service = new AccessGraphService(auditRepo, blastRadius, classificationRepo);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);

        // Observed: priya → advisor (acts-through, 3), advisor → market-data.quote (invoked, allowed, 2).
        when(auditRepo.aggregateActorAgentEdges(eq(TENANT), any())).thenReturn(List.<Object[]>of(
                new Object[]{"priya", "uid-1", null, "advisor", null, 3L},      // human-rooted
                new Object[]{"svc-bot", null, "nhi-9", "advisor", null, 1L}));  // machine-rooted (NHI)
        when(auditRepo.aggregateAgentToolEdges(eq(AuditEventType.PDP_DECISION_RENDERED), eq(TENANT), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"advisor", null, "market-data.quote", "market-data", AuditStatus.SUCCESS, 2L},
                        new Object[]{"advisor", null, "secret.op", "vault", AuditStatus.DENIED, 1L}));

        // Entitled: advisor is granted market-data.quote (used) AND news.sentiment (never used → over-privilege).
        ResourceReach used = new ResourceReach("TOOL", "market-data.quote", "ACTIVE",
                List.of("allow-market"), true, 2L, LocalDateTime.now());
        ResourceReach unused = new ResourceReach("TOOL", "news.sentiment", "ACTIVE",
                List.of("allow-news"), false, 0L, null);
        when(blastRadius.getAgentBlastRadius("advisor")).thenReturn(
                new AgentBlastRadius("advisor", false, List.of(used, unused),
                        new ReachSummary(2, 0, 0, 0, 1), List.of()));

        // DLP: market-data.quote returns RESTRICTED data. Row: [producer, capabilityName, type, protocol, sensitivity, count, lastSeen].
        when(classificationRepo.capabilityProfileRows(TENANT)).thenReturn(List.<Object[]>of(
                new Object[]{"market-data", "market-data.quote", "TOOL", "MCP", "RESTRICTED", 5L, null}));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void mergesObservedAndEntitled_flaggingOverPrivilegeGap() {
        AccessGraph g = service.build(null);

        // Nodes: the human, the agent, and both tools (one reached, one only granted).
        assertThat(g.nodes()).extracting(AccessGraph.Node::id)
                .contains("human:priya", "agent:advisor", "tool:market-data.quote", "tool:news.sentiment");

        // The used grant is "in balance" — observed AND entitled, no gap.
        AccessGraph.Edge used = edge(g, "agent:advisor", "invoked", "tool:market-data.quote");
        assertThat(used.observed()).isTrue();
        assertThat(used.entitled()).isTrue();
        assertThat(used.grantStatus()).isEqualTo("ACTIVE");
        assertThat(used.gap()).isNull();

        // The unused grant is the whole point: entitled, never observed → OVER_PRIVILEGE.
        AccessGraph.Edge over = edge(g, "agent:advisor", "invoked", "tool:news.sentiment");
        assertThat(over.entitled()).isTrue();
        assertThat(over.observed()).isFalse();
        assertThat(over.gap()).isEqualTo("OVER_PRIVILEGE");

        // Summary and node flags reflect the over-privilege.
        assertThat(g.summary().overPrivilegeEdges()).isEqualTo(1);
        AccessGraph.Node agent = g.nodes().stream().filter(n -> n.id().equals("agent:advisor")).findFirst().orElseThrow();
        assertThat(agent.flags()).contains("OVER_PRIVILEGED");

        // The human→agent leg is present and observed.
        assertThat(edge(g, "human:priya", "acts-through", "agent:advisor").observed()).isTrue();

        // The actor side is typed: priya is HUMAN, the service identity is NHI.
        assertThat(g.nodes()).filteredOn(n -> n.id().equals("nhi:svc-bot")).singleElement()
                .extracting(AccessGraph.Node::type).isEqualTo("NHI");
        assertThat(g.summary().humans()).isEqualTo(1);
        assertThat(g.summary().nhis()).isEqualTo(1);

        // Sensitivity: the reached tool carries its RESTRICTED data class, and the agent that pulled it is
        // flagged for sensitive reach.
        AccessGraph.Node quote = g.nodes().stream().filter(n -> n.id().equals("tool:market-data.quote"))
                .findFirst().orElseThrow();
        assertThat(quote.dataClass()).isEqualTo("RESTRICTED");
        assertThat(agent.flags()).contains("SENSITIVE_REACH");

        // A denied-only edge is blocked, not "ungoverned use": allowed=0 ⇒ no gap, risk HIGH.
        AccessGraph.Edge denied = edge(g, "agent:advisor", "invoked", "tool:secret.op");
        assertThat(denied.allowed()).isZero();
        assertThat(denied.denied()).isEqualTo(1);
        assertThat(denied.gap()).isNull();
        assertThat(denied.riskBand()).isEqualTo("HIGH");

        // Accountability: the human inherits the risk of the agent it delegates to (advisor is HIGH), rather than
        // reading LOW off its benign acts-through edge.
        AccessGraph.Node human = g.nodes().stream().filter(n -> n.id().equals("human:priya")).findFirst().orElseThrow();
        assertThat(human.riskBand()).isEqualTo("HIGH");
    }

    private static AccessGraph.Edge edge(AccessGraph g, String source, String relation, String target) {
        return g.edges().stream()
                .filter(e -> e.source().equals(source) && e.relation().equals(relation) && e.target().equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("edge not found: " + source + " -" + relation + "-> " + target));
    }
}
