package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeEvents;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot;
import com.ws.wsAgenticSecurityGateway.ciso.dto.PointInTimeSnapshot.PolicyInForce;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayPolicyEntity;
import com.ws.wsAgenticSecurityGateway.pdp.repository.GatewayPolicyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Point-in-time reconstruction: window aggregates (enforcement, agents, effective policies, accountability) come
 * from the time-bounded ledger queries; effective policies are cross-checked against current state (present/enabled);
 * unrooted = governed − accountable; and the window defaults to the ledger span. Pins the replay.
 */
class PointInTimeServiceTest {

    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final GatewayPolicyRepository policyRepo = mock(GatewayPolicyRepository.class);
    private final PointInTimeService service = new PointInTimeService(pdpRepo, policyRepo);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        when(pdpRepo.ledgerSpan("acme")).thenReturn(List.<Object[]>of(new Object[]{
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 24, 9, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 13, 18, 0)) }));
        when(pdpRepo.windowEnforcement(eq("acme"), any(), any())).thenReturn(List.of(
                new Object[]{ "ALLOW", "POLICY_MATCH", 100L },
                new Object[]{ "DENY", "DEFAULT_DENY", 2L },
                new Object[]{ "DENY", "POLICY_MATCH", 3L }));
        when(pdpRepo.windowAgentActivity(eq("acme"), any(), any())).thenReturn(List.of(
                new Object[]{ "advisor", 50L, 48L, 2L, 5L },
                new Object[]{ "billing", 5L, 0L, 5L, 1L }));
        when(pdpRepo.windowPolicyActivity(eq("acme"), any(), any())).thenReturn(List.of(
                new Object[]{ "financial-desk-grant", 40L },
                new Object[]{ "retired-grant", 3L }));
        when(pdpRepo.windowAccountability(eq("acme"), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{ 100L, 97L, 1L }));   // total, accountable, distinct humans
        when(policyRepo.findAllByWsTenantName("acme")).thenReturn(List.of(
                GatewayPolicyEntity.builder().cedarPolicyId("financial-desk-grant").enabled(true).build()));
        // Evidence rows: one human-attributed ALLOW, one anonymous DENY (null human).
        when(pdpRepo.windowEvents(eq("acme"), any(), any(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(
                new Object[]{ Timestamp.valueOf(LocalDateTime.of(2026, 8, 6, 14, 3)), "advisor", "amit",
                        "skillInvocation", "market-data.quote", "ALLOW", "financial-desk-grant", "c1" },
                new Object[]{ Timestamp.valueOf(LocalDateTime.of(2026, 8, 6, 14, 2)), "unknown", null,
                        "skillInvocation", "billing.get_quote", "DENY", "-", "c2" }));
        when(pdpRepo.windowEventsCount(eq("acme"), any(), any(), anyString(), anyString())).thenReturn(2L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reconstructsWindow_enforcement_agents_effectivePolicies_accountability() {
        PointInTimeSnapshot s = service.getSnapshot(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 8, 13));

        assertThat(s.windowFrom()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(s.windowTo()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(s.ledgerStart()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(s.ledgerEnd()).isEqualTo(LocalDate.of(2026, 8, 13));

        PointInTimeSnapshot.Summary sum = s.summary();
        assertThat(sum.decisions()).isEqualTo(105);
        assertThat(sum.allowed()).isEqualTo(100);
        assertThat(sum.denied()).isEqualTo(5);
        assertThat(sum.defaultDeny()).isEqualTo(2);
        assertThat(sum.forbidDeny()).isEqualTo(3);
        assertThat(sum.activeAgents()).isEqualTo(2);
        assertThat(sum.governedEvaluations()).isEqualTo(100);
        assertThat(sum.distinctHumans()).isEqualTo(1);
        assertThat(sum.accountable()).isEqualTo(97);
        assertThat(sum.unrooted()).isEqualTo(3);          // 100 − 97
        assertThat(sum.effectivePolicies()).isEqualTo(2);

        // Effective policies cross-checked against current state.
        PolicyInForce live = s.effectivePolicies().stream()
                .filter(p -> p.policyId().equals("financial-desk-grant")).findFirst().orElseThrow();
        assertThat(live.decisions()).isEqualTo(40);
        assertThat(live.stillPresent()).isTrue();
        assertThat(live.stillEnabled()).isTrue();
        PolicyInForce gone = s.effectivePolicies().stream()
                .filter(p -> p.policyId().equals("retired-grant")).findFirst().orElseThrow();
        assertThat(gone.stillPresent()).isFalse();        // decided then, not in the registry now
        assertThat(gone.stillEnabled()).isFalse();

        assertThat(s.agents()).extracting(PointInTimeSnapshot.AgentSnapshot::agentName)
                .containsExactly("advisor", "billing");
        assertThat(s.agents().get(0).allowed()).isEqualTo(48);
        assertThat(s.agents().get(0).distinctResources()).isEqualTo(5);

        assertThat(s.notes()).anyMatch(n -> n.contains("recorded authorization decisions"));

        // The overview carries a small evidence sample so it tells a story on its own.
        assertThat(s.recentEvents()).hasSize(2);
        assertThat(s.recentEvents().get(0).agent()).isEqualTo("advisor");
        assertThat(s.recentEvents().get(0).human()).isEqualTo("amit");
        assertThat(s.recentEvents().get(1).human()).isNull();   // anonymous caller → null human, honestly
    }

    @Test
    void events_drillDown_filtersNormalized_humanPerRow_paginated() {
        PointInTimeEvents e = service.getEvents(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 8, 13),
                "advisor", "allow", 0, 50);

        assertThat(e.agentFilter()).isEqualTo("advisor");
        assertThat(e.decisionFilter()).isEqualTo("ALLOW");     // normalized to upper-case
        assertThat(e.totalEvents()).isEqualTo(2);
        assertThat(e.events()).hasSize(2);
        assertThat(e.events().get(0).at()).isEqualTo(LocalDateTime.of(2026, 8, 6, 14, 3));
        assertThat(e.events().get(0).human()).isEqualTo("amit");
        assertThat(e.events().get(0).decision()).isEqualTo("ALLOW");
        assertThat(e.hasNext()).isFalse();                     // 2 rows fit in one page of 50
        assertThat(e.size()).isEqualTo(50);
    }

    @Test
    void events_pagination_hasNext_andSizeClamped() {
        PointInTimeEvents small = service.getEvents(null, null, null, null, 0, 1);
        assertThat(small.size()).isEqualTo(1);
        assertThat(small.totalEvents()).isEqualTo(2);
        assertThat(small.hasNext()).isTrue();                  // (0+1)*1 < 2

        PointInTimeEvents huge = service.getEvents(null, null, null, null, 0, 100_000);
        assertThat(huge.size()).isEqualTo(500);                // clamped to MAX_PAGE_SIZE
    }

    @Test
    void defaultsWindowToLedgerStart_whenFromNotGiven() {
        PointInTimeSnapshot s = service.getSnapshot(null, LocalDate.of(2026, 8, 1));
        assertThat(s.windowFrom()).isEqualTo(LocalDate.of(2026, 7, 24));   // = ledger start
        assertThat(s.windowTo()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void invertedWindow_isDefensivelyClamped_notSilentlyInverted() {
        // The controller rejects an explicit reversed window with 400; the service must never emit an inverted
        // (windowFrom > windowTo) snapshot — it clamps 'from' down to 'to'.
        PointInTimeSnapshot s = service.getSnapshot(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1));
        assertThat(s.windowFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(s.windowTo()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(s.windowFrom()).isBeforeOrEqualTo(s.windowTo());
    }
}
