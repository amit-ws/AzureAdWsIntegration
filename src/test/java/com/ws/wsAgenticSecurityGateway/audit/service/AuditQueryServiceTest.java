package com.ws.wsAgenticSecurityGateway.audit.service;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the trace-chain assembly: {@code getTraceChain} returns a request's full lifecycle by
 * {@code trace_id}, ordered by event sequence — the data the dashboard's trace view renders.
 */
class AuditQueryServiceTest {

    private final GatewayAuditLogRepository auditRepo = mock(GatewayAuditLogRepository.class);
    private final PdpAuditLogRepository pdpAuditRepo = mock(PdpAuditLogRepository.class);
    private final AuditQueryService service = new AuditQueryService(auditRepo, pdpAuditRepo);

    private static GatewayAuditLog event(String traceId, String corrId, int seq, AuditEventType type) {
        return GatewayAuditLog.builder()
                .traceId(traceId).correlationId(corrId).eventSequence(seq)
                .module(AuditModule.ORCHESTRATION_LAYER).eventType(type)
                .timestamp(LocalDateTime.now()).build();
    }

    @Test
    void getTraceChain_returnsTraceEvents_orderedBySequence() {
        // returned out of order; the chain must be ordered by eventSequence
        when(auditRepo.findByTraceId("trace-1")).thenReturn(List.of(
                event("trace-1", "corr-1", 3, AuditEventType.STS_TOKEN_MINTED),
                event("trace-1", "corr-1", 1, AuditEventType.AGENT_DEPROVISIONED)));
        when(pdpAuditRepo.findByCorrelationId(any())).thenReturn(List.of());

        List<GatewayAuditLog> chain = service.getTraceChain("trace-1");

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getEventSequence()).isEqualTo(1);
        assertThat(chain.get(1).getEventSequence()).isEqualTo(3);
        assertThat(chain).allMatch(e -> "trace-1".equals(e.getTraceId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryLogs_flagsEveryEventOfALegThatMintedAnOboToken() {
        // leg-A minted a token (has a STS_TOKEN_MINTED row); leg-B did not
        GatewayAuditLog mintRow  = event("t", "leg-A", 1, AuditEventType.STS_TOKEN_MINTED);
        GatewayAuditLog toolRow  = event("t", "leg-A", 2, AuditEventType.ORCHESTRATION_RESPONSE_RETURNED);
        GatewayAuditLog otherLeg = event("t", "leg-B", 1, AuditEventType.PDP_DECISION_RENDERED);
        Page<GatewayAuditLog> page = new PageImpl<>(List.of(mintRow, toolRow, otherLeg));

        when(auditRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
        when(auditRepo.findCorrelationIdsWithEventType(eq(AuditEventType.STS_TOKEN_MINTED), any()))
                .thenReturn(List.of("leg-A"));

        Page<GatewayAuditLog> result = service.queryLogs(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, PageRequest.of(0, 20));

        // every event of the minted leg is flagged (not just the mint row); the non-minting leg is not
        assertThat(result.getContent()).filteredOn(r -> "leg-A".equals(r.getCorrelationId()))
                .hasSize(2).allMatch(GatewayAuditLog::isHasOboReceipt);
        assertThat(result.getContent()).filteredOn(r -> "leg-B".equals(r.getCorrelationId()))
                .allMatch(r -> !r.isHasOboReceipt());
    }
}
