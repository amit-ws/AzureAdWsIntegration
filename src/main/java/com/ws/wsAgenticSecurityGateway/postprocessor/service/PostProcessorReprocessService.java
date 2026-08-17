package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ws.wsAgenticSecurityGateway.audit.entity.GatewayAuditLog;
import com.ws.wsAgenticSecurityGateway.audit.repository.GatewayAuditLogRepository;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.DetectionResult;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.EgressClassifier;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.RulePolicy;
import com.ws.wsAgenticSecurityGateway.postprocessor.dto.ClassificationView;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-classifies a response on demand from its RETAINED raw (kept in the audit trail's {@code response_payload}),
 * so admins can fix a "not scanned" gap or re-tag with a newly-added rule — without the post-processor itself
 * ever storing the raw. Tenant-scoped; uses the tenant's current {@link ClassifierRuleService} policy so a
 * reprocess reflects the latest rules.
 *
 * <p>If a hop's raw was not retained (metadata-only event), reprocess reports it as skipped rather than failing.
 */
@Service
@Slf4j
public class PostProcessorReprocessService {

    private static final int MAX_BULK = 500;

    private final GatewayResponseClassificationRepository classificationRepo;
    private final GatewayAuditLogRepository auditRepo;
    private final EgressClassifier classifier;
    private final ClassifierRuleService ruleService;

    public PostProcessorReprocessService(GatewayResponseClassificationRepository classificationRepo,
                                         GatewayAuditLogRepository auditRepo,
                                         EgressClassifier classifier,
                                         ClassifierRuleService ruleService) {
        this.classificationRepo = classificationRepo;
        this.auditRepo = auditRepo;
        this.classifier = classifier;
        this.ruleService = ruleService;
    }

    /** Re-classify one hop by its correlation id, updating (or creating) its classification row. */
    public ClassificationView reprocess(String correlationId) {
        String tenant = TenantContext.get();
        GatewayResponseClassificationEntity row = reprocessOne(tenant, correlationId, ruleService.policyFor(tenant));
        if (row == null) {
            throw new IllegalStateException("No retained response is available to reprocess for this event.");
        }
        log.info("Reprocessed egress classification corr={} → sensitivity={}", correlationId, row.getSensitivity());
        return ClassificationView.from(row);
    }

    /** Re-classify a CHOSEN set of hops (by correlation id) against the current rules — the "reprocess selected" path. */
    public Map<String, Object> reprocessMany(List<String> correlationIds) {
        String tenant = TenantContext.get();
        RulePolicy policy = ruleService.policyFor(tenant);
        List<String> ids = correlationIds == null ? List.of() : correlationIds;
        int updated = 0;
        int skipped = 0;
        for (String correlationId : ids) {
            try {
                if (reprocessOne(tenant, correlationId, policy) != null) {
                    updated++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                skipped++; // one bad row never fails the batch
            }
        }
        return summary(ids.size(), updated, skipped, false);
    }

    /** Re-classify all of the tenant's recorded classifications against the current rules (page-capped). */
    public Map<String, Object> reprocessAll() {
        String tenant = TenantContext.get();
        RulePolicy policy = ruleService.policyFor(tenant);
        List<GatewayResponseClassificationEntity> rows =
                classificationRepo.findByWsTenantNameOrderByClassifiedAtDesc(tenant, PageRequest.of(0, MAX_BULK));
        int updated = 0;
        int skipped = 0;
        for (GatewayResponseClassificationEntity row : rows) {
            if (reprocessOne(tenant, row.getCorrelationId(), policy) != null) {
                updated++;
            } else {
                skipped++;
            }
        }
        return summary(rows.size(), updated, skipped, rows.size() >= MAX_BULK);
    }

    /**
     * Re-classify one hop from its RETAINED raw (audit {@code response_payload}) with the given policy, upserting
     * its row. Returns the saved row, or {@code null} if no raw was retained (so callers count it as skipped).
     */
    private GatewayResponseClassificationEntity reprocessOne(String tenant, String correlationId, RulePolicy policy) {
        String raw = rawResponseFor(tenant, correlationId);
        if (raw == null) {
            return null;
        }
        DetectionResult d = classifier.classify(raw, policy);
        List<GatewayResponseClassificationEntity> existing =
                classificationRepo.findByWsTenantNameAndCorrelationId(tenant, correlationId);
        GatewayResponseClassificationEntity row =
                existing.isEmpty() ? buildFromAudit(tenant, correlationId) : existing.get(0);
        applyDetection(row, d, raw);
        return classificationRepo.save(row);
    }

    private static Map<String, Object> summary(int total, int updated, int skipped, boolean capped) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("updated", updated);
        result.put("skipped", skipped);
        if (capped) {
            result.put("note", "Capped at " + MAX_BULK + " most-recent classifications.");
        }
        return result;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** The retained raw response for a correlation, as text — or null if no response was retained for it. */
    private String rawResponseFor(String tenant, String correlationId) {
        if (correlationId == null) {
            return null;
        }
        for (GatewayAuditLog a : auditRepo.findByCorrelationIdAndWsTenantName(correlationId, tenant)) {
            JsonNode payload = a.getResponsePayload();
            if (payload != null && !payload.isNull()) {
                String text = payload.toString();
                if (text != null && !text.isBlank() && !"null".equals(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static void applyDetection(GatewayResponseClassificationEntity row, DetectionResult d, String raw) {
        row.setDataCategories(new ArrayList<>(d.categories()));
        row.setSensitivity(d.sensitivity());
        row.setDetectors(d.detectors());
        row.setInjectionDetected(d.injectionDetected());
        row.setVolumeBytes((long) raw.getBytes(StandardCharsets.UTF_8).length);
        row.setClassifierVersion("v1");
        row.setStatus("CLASSIFIED");
        row.setClassifiedAt(LocalDateTime.now());
    }

    /** Build a fresh classification row from the audit event's metadata (for a previously-missing classification). */
    private GatewayResponseClassificationEntity buildFromAudit(String tenant, String correlationId) {
        GatewayAuditLog a = auditRepo.findByCorrelationIdAndWsTenantName(correlationId, tenant).stream()
                .filter(x -> x.getResponsePayload() != null && !x.getResponsePayload().isNull())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No retained response is available to reprocess."));
        return GatewayResponseClassificationEntity.builder()
                .wsTenantName(tenant)
                .correlationId(correlationId)
                .traceId(a.getTraceId())
                .protocol(a.getProtocol())
                .capabilityType(a.getCapabilityType())
                .capabilityName(a.getCapabilityName())
                .producer(a.getServerName())
                .consumer(a.getAgentName())
                .direction("RESPONSE")
                .schemaVersion("1")
                .enforcementMode("OBSERVE")
                .actionTaken("OBSERVED")
                .terminalEgress(false)
                .redacted(false)
                .build();
    }
}
