package com.ws.wsAgenticSecurityGateway.ciso.service;

import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail.ActivityEntry;
import com.ws.wsAgenticSecurityGateway.ciso.dto.AgentActivityTrail.TrailSummary;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Activity-trail assembly: OUTBOUND uses the agent as actor, INBOUND uses the caller; allow/deny + distinct
 * counts + shared-correlation de-dup + period span all come from the mocked ledger rows. Pins the forensic merge.
 */
class AgentActivityTrailServiceTest {

    private final PdpAuditLogRepository pdpRepo = mock(PdpAuditLogRepository.class);
    private final GatewayAuditLogRepository auditRepo = mock(GatewayAuditLogRepository.class);
    private final AgentActivityTrailService service = new AgentActivityTrailService(pdpRepo, auditRepo);

    @BeforeEach
    void setTenant() {
        TenantContext.set("acme");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void buildsBothDirections_withActorsCountsAndPeriod() {
        LocalDateTime t13 = LocalDateTime.of(2026, 8, 13, 10, 0);
        LocalDateTime t13b = LocalDateTime.of(2026, 8, 13, 9, 0);
        LocalDateTime t12 = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime t11 = LocalDateTime.of(2026, 8, 11, 10, 0);

        // OUTBOUND (advisor is the subject): 1 allow + 1 deny across 2 distinct resources.
        when(pdpRepo.agentOutboundActivity(anyString(), eq("advisor"), anyInt())).thenReturn(List.of(
                new Object[]{ Timestamp.valueOf(t13), "skillInvocation", "market-data.quote", "ALLOW", "grant", "corr-1", "advisor" },
                new Object[]{ Timestamp.valueOf(t13b), "toolCall", "alphavantage_GLOBAL_QUOTE", "DENY", "block", "corr-2", "advisor" }));
        // INBOUND (someone invoked advisor.analyze): 2 callers, both allow; one shares corr-1 with outbound.
        when(pdpRepo.agentInboundActivity(anyString(), eq("advisor.%"), anyInt())).thenReturn(List.of(
                new Object[]{ Timestamp.valueOf(t12), "skillInvocation", "advisor.analyze", "ALLOW", "grant", "corr-3", "agent-console" },
                new Object[]{ Timestamp.valueOf(t11), "skillInvocation", "advisor.analyze", "ALLOW", "grant", "corr-1", "claude-desktop" }));
        when(auditRepo.countDistinctHumansForAgent(anyString(), eq("advisor"))).thenReturn(1L);

        AgentActivityTrail p = service.getActivityTrail("advisor");

        assertThat(p.agentName()).isEqualTo("advisor");
        assertThat(p.tenant()).isEqualTo("acme");

        // OUTBOUND: the agent itself is the actor.
        assertThat(p.outbound()).hasSize(2).allMatch(e -> "advisor".equals(e.actor()));
        // INBOUND: the caller is the actor; the resource is the agent's own skill.
        assertThat(p.inbound()).extracting(ActivityEntry::actor).containsExactly("agent-console", "claude-desktop");
        assertThat(p.inbound()).allMatch(e -> "advisor.analyze".equals(e.resource()));

        TrailSummary s = p.summary();
        assertThat(s.outboundActions()).isEqualTo(2);
        assertThat(s.outboundAllowed()).isEqualTo(1);
        assertThat(s.outboundDenied()).isEqualTo(1);
        assertThat(s.inboundActions()).isEqualTo(2);
        assertThat(s.inboundAllowed()).isEqualTo(2);
        assertThat(s.inboundDenied()).isEqualTo(0);
        assertThat(s.distinctResourcesReached()).isEqualTo(2);   // market-data.quote + alphavantage_GLOBAL_QUOTE
        assertThat(s.distinctCallers()).isEqualTo(2);            // agent-console + claude-desktop
        assertThat(s.humansServed()).isEqualTo(1);
        assertThat(s.distinctRequests()).isEqualTo(3);           // corr-1 (shared), corr-2, corr-3

        assertThat(p.periodStart()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(p.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 13));

        String csv = service.toCsv(p);
        assertThat(csv).contains("direction,at,actor,action,resource,decision,policy,correlation_id");
        assertThat(csv).contains("OUTBOUND").contains("INBOUND").contains("market-data.quote");
    }
}
