package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.CapabilityFingerprint;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.DriftSignal;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.InsightsReport;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.SharingEdge;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the Insights pivot: fingerprint peak + sensitive-count aggregation, the sharing edge, and drift escalation
 * (recent sensitivity above the earlier baseline).
 */
class PostProcessorInsightsServiceTest {

    private final GatewayResponseClassificationRepository repo = mock(GatewayResponseClassificationRepository.class);
    private final PostProcessorInsightsService svc = new PostProcessorInsightsService(repo);

    @BeforeEach
    void setUp() {
        TenantContext.set("t1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void insights_pivotsFingerprintsSharingAndDrift() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 17, 12, 0);

        // One capability seen as PUBLIC ×5 and RESTRICTED ×2 → peak RESTRICTED, sensitiveCount 2, total 7.
        when(repo.capabilityProfileRows("t1")).thenReturn(List.of(
                new Object[]{"alphavantage", "NEWS_SENTIMENT", "TOOL", "MCP", "PUBLIC", 5L, now},
                new Object[]{"alphavantage", "NEWS_SENTIMENT", "TOOL", "MCP", "RESTRICTED", 2L, now}));

        // One edge news → advisor carrying CONFIDENTIAL ×3.
        when(repo.sharingEdgeRows("t1")).thenReturn(List.<Object[]>of(
                new Object[]{"news", "advisor", "CONFIDENTIAL", 3L, now}));

        // Drift: baseline was PUBLIC, recent window shows RESTRICTED → escalation.
        when(repo.driftRows(eq("t1"), any(LocalDateTime.class))).thenReturn(List.of(
                new Object[]{"alphavantage", "NEWS_SENTIMENT", "PUBLIC", Boolean.FALSE, 10L},
                new Object[]{"alphavantage", "NEWS_SENTIMENT", "RESTRICTED", Boolean.TRUE, 1L}));

        InsightsReport report = svc.insights();

        assertThat(report.fingerprints()).hasSize(1);
        CapabilityFingerprint fp = report.fingerprints().get(0);
        assertThat(fp.total()).isEqualTo(7);
        assertThat(fp.sensitiveCount()).isEqualTo(2);
        assertThat(fp.peakSensitivity()).isEqualTo("RESTRICTED");
        assertThat(fp.bySensitivity()).containsEntry("PUBLIC", 5L).containsEntry("RESTRICTED", 2L);

        assertThat(report.sharingEdges()).hasSize(1);
        SharingEdge edge = report.sharingEdges().get(0);
        assertThat(edge.producer()).isEqualTo("news");
        assertThat(edge.consumer()).isEqualTo("advisor");
        assertThat(edge.sensitiveCount()).isEqualTo(3);
        assertThat(edge.peakSensitivity()).isEqualTo("CONFIDENTIAL");

        assertThat(report.drift()).hasSize(1);
        DriftSignal drift = report.drift().get(0);
        assertThat(drift.capabilityName()).isEqualTo("NEWS_SENTIMENT");
        assertThat(drift.baselinePeak()).isEqualTo("PUBLIC");
        assertThat(drift.recentPeak()).isEqualTo("RESTRICTED");
        assertThat(drift.recentCount()).isEqualTo(1);
    }

    @Test
    void entitySensitivity_rollsUpPerAgentServerTool() {
        java.util.UUID agent = java.util.UUID.randomUUID();
        java.util.UUID server = java.util.UUID.randomUUID();

        // Agent: as consumer PUBLIC, as producer RESTRICTED → peak RESTRICTED.
        when(repo.consumerAgentSensitivity("t1")).thenReturn(List.<Object[]>of(
                new Object[]{agent, "PUBLIC", 3L}));
        when(repo.producerAgentSensitivity("t1")).thenReturn(List.<Object[]>of(
                new Object[]{agent, "RESTRICTED", 1L}));
        // Server: CONFIDENTIAL (by id + by name).
        when(repo.serverSensitivity("t1")).thenReturn(List.<Object[]>of(
                new Object[]{server, "alphavantage", "CONFIDENTIAL", 2L}));
        // One tool: INTERNAL.
        when(repo.serverCapabilitySensitivity("t1")).thenReturn(List.<Object[]>of(
                new Object[]{server, "alphavantage", "TOOL", "GLOBAL_QUOTE", "INTERNAL", 5L}));
        // One skill: RESTRICTED (agentId + skill name).
        when(repo.skillSensitivity("t1")).thenReturn(List.<Object[]>of(
                new Object[]{agent, "advisor", "advisor.analyze", "RESTRICTED", 1L}));

        var es = svc.entitySensitivity();
        assertThat(es.agents()).containsEntry(agent.toString(), "RESTRICTED");
        assertThat(es.servers()).containsEntry(server.toString(), "CONFIDENTIAL");
        assertThat(es.serversByName()).containsEntry("alphavantage", "CONFIDENTIAL");
        assertThat(es.tools()).hasSize(1);
        assertThat(es.tools().get(0).capabilityName()).isEqualTo("GLOBAL_QUOTE");
        assertThat(es.tools().get(0).peakSensitivity()).isEqualTo("INTERNAL");
        assertThat(es.skills()).hasSize(1);
        assertThat(es.skills().get(0).producerAgentId()).isEqualTo(agent.toString());
        assertThat(es.skills().get(0).capabilityName()).isEqualTo("advisor.analyze");
        assertThat(es.skills().get(0).peakSensitivity()).isEqualTo("RESTRICTED");
    }

    @Test
    void insights_noDriftWhenNoBaseline() {
        when(repo.capabilityProfileRows(anyString())).thenReturn(List.of());
        when(repo.sharingEdgeRows(anyString())).thenReturn(List.of());
        // Only recent data, no baseline → first-seen, not drift.
        when(repo.driftRows(eq("t1"), any(LocalDateTime.class))).thenReturn(List.<Object[]>of(
                new Object[]{"p", "cap", "RESTRICTED", Boolean.TRUE, 3L}));

        assertThat(svc.insights().drift()).isEmpty();
    }
}
