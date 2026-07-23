package com.ws.wsAgenticSecurityGateway.audit.service;

import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditModule;
import com.ws.wsAgenticSecurityGateway.audit.entity.McpAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.McpAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.audit.repository.PdpAuditLogRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests the trace-chain assembly: {@code getTraceChain} returns a request's full lifecycle by
 * {@code trace_id}, ordered by event sequence — the data the dashboard's trace view renders.
 */
class AuditQueryServiceTest {

    private final McpAuditLogRepository auditRepo = mock(McpAuditLogRepository.class);
    private final PdpAuditLogRepository pdpAuditRepo = mock(PdpAuditLogRepository.class);
    private final AuditQueryService service = new AuditQueryService(auditRepo, pdpAuditRepo);

    private static McpAuditLog event(String traceId, String corrId, int seq, AuditEventType type) {
        return McpAuditLog.builder()
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

        List<McpAuditLog> chain = service.getTraceChain("trace-1");

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getEventSequence()).isEqualTo(1);
        assertThat(chain.get(1).getEventSequence()).isEqualTo(3);
        assertThat(chain).allMatch(e -> "trace-1".equals(e.getTraceId()));
    }
}
