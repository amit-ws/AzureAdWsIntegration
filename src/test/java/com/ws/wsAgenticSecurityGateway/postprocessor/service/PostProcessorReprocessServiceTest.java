package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.constants.AuditEventType;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.EgressClassifier;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.RulePolicy;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins reprocess: it re-classifies from the retained raw (audit {@code response_payload}) with the current rules,
 * updates the existing row, and refuses cleanly when no raw was retained.
 */
class PostProcessorReprocessServiceTest {

    private final GatewayResponseClassificationRepository classRepo = mock(GatewayResponseClassificationRepository.class);
    private final GatewayAuditLogRepository auditRepo = mock(GatewayAuditLogRepository.class);
    private final ClassifierRuleService ruleService = mock(ClassifierRuleService.class);
    private final EgressClassifier classifier = new EgressClassifier();

    private final PostProcessorReprocessService svc =
            new PostProcessorReprocessService(classRepo, auditRepo, classifier, ruleService);

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TenantContext.set("t1");
        when(ruleService.policyFor(anyString())).thenReturn(RulePolicy.empty());
        when(classRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void reprocess_reclassifiesExistingRow_fromRetainedRaw() throws Exception {
        // With the tool-invocation event under the caller's tenant (as it should be), reprocess finds the retained
        // response and re-classifies it against the current rules.
        GatewayAuditLog audit = new GatewayAuditLog();
        audit.setEventType(AuditEventType.CLIENT_TOOL_INVOCATION);
        audit.setResponsePayload(mapper.readTree("\"employee record SSN 123-45-6789\""));
        when(auditRepo.findByCorrelationIdAndWsTenantName("C1", "t1")).thenReturn(List.of(audit));

        GatewayResponseClassificationEntity existing = GatewayResponseClassificationEntity.builder()
                .wsTenantName("t1").correlationId("C1").sensitivity("PUBLIC").build();
        when(classRepo.findByWsTenantNameAndCorrelationId("t1", "C1")).thenReturn(List.of(existing));

        ClassificationView v = svc.reprocess("C1");

        assertThat(v.sensitivity()).isEqualTo("RESTRICTED");
        assertThat(v.dataCategories()).contains("PII");
        assertThat(v.detectors()).containsKey("ssn");
    }

    @Test
    void reprocess_noRetainedRaw_isRefusedCleanly() {
        when(auditRepo.findByCorrelationIdAndWsTenantName("C2", "t1")).thenReturn(List.of());
        assertThatThrownBy(() -> svc.reprocess("C2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No retained response");
    }

    @Test
    void reprocess_ignoresNonCapabilityPayloads_likeStsToken() throws Exception {
        // The STS-token / PDP-decision payloads share the correlation and would look classifiable — they must be
        // ignored so we never classify a token or a policy decision instead of the tool response.
        GatewayAuditLog sts = new GatewayAuditLog();
        sts.setEventType(AuditEventType.STS_TOKEN_MINTED);
        sts.setResponsePayload(mapper.readTree("\"minted token for holder with SSN 123-45-6789\""));
        when(auditRepo.findByCorrelationIdAndWsTenantName("C3", "t1")).thenReturn(List.of(sts));

        assertThatThrownBy(() -> svc.reprocess("C3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No retained response");
    }
}
