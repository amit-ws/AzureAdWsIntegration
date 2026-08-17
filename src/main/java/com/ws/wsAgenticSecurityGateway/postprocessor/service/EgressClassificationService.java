package com.ws.wsAgenticSecurityGateway.postprocessor.service;

import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.DetectionResult;
import com.ws.wsAgenticSecurityGateway.postprocessor.classifier.EgressClassifier;
import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import com.ws.wsAgenticSecurityGateway.postprocessor.model.EgressContext;
import com.ws.wsAgenticSecurityGateway.postprocessor.repository.GatewayResponseClassificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * The post-processor's egress observe/tag pipeline (V1 — metadata only, no enforcement). Runs OFF the hot path:
 * the orchestrator hands it the response text + governance context and returns immediately; this classifies and
 * writes one {@link GatewayResponseClassificationEntity} per response hop.
 *
 * <p><b>Fail-open</b> — a tagging failure NEVER breaks the call; it is logged and the row is skipped (which the UI
 * surfaces as "not scanned"). <b>Metadata only</b> — the raw response is classified and dropped; only categories,
 * sensitivity, detector evidence, and volume are persisted. The row links to its audit event by
 * {@code correlation_id} (always present) and, best-effort, {@code source_event_id}.
 */
@Service
@Slf4j
public class EgressClassificationService {

    private static final String CLASSIFIER_VERSION = "v1";
    private static final String SCHEMA_VERSION = "1";

    private final EgressClassifier classifier;
    private final GatewayResponseClassificationRepository repository;

    public EgressClassificationService(EgressClassifier classifier,
                                       GatewayResponseClassificationRepository repository) {
        this.classifier = classifier;
        this.repository = repository;
    }

    /**
     * Classify one response hop asynchronously and persist its classification row. Reuses the audit executor so it
     * stays off the request thread (which is why the tenant rides on {@link EgressContext}, not TenantContext).
     */
    @Async("auditExecutor")
    public void classifyAsync(EgressContext ctx, String responseText) {
        long start = System.currentTimeMillis();
        try {
            // Idempotency: one classification per leg. Skip if this correlation is already classified (retry/reprocess
            // goes through the explicit reprocess path, not this hook).
            if (ctx.correlationId() != null && ctx.tenant() != null
                    && !repository.findByWsTenantNameAndCorrelationId(ctx.tenant(), ctx.correlationId()).isEmpty()) {
                return;
            }

            DetectionResult d = classifier.classify(responseText);
            long bytes = responseText == null ? 0L : responseText.getBytes(StandardCharsets.UTF_8).length;

            GatewayResponseClassificationEntity row = GatewayResponseClassificationEntity.builder()
                    .wsTenantName(ctx.tenant())
                    .correlationId(ctx.correlationId())
                    .traceId(ctx.traceId())
                    .sourceEventId(ctx.sourceEventId())
                    .protocol(ctx.protocol())
                    .capabilityType(ctx.capabilityType())
                    .capabilityName(ctx.capabilityName())
                    .producer(ctx.producer())
                    .consumer(ctx.consumer())
                    .direction("RESPONSE")
                    .dataCategories(new ArrayList<>(d.categories()))
                    .sensitivity(d.sensitivity())
                    .volumeBytes(bytes)
                    .injectionDetected(d.injectionDetected())
                    .detectors(d.detectors())
                    .redacted(false)
                    .classifierVersion(CLASSIFIER_VERSION)
                    .status("CLASSIFIED")
                    .schemaVersion(SCHEMA_VERSION)
                    .durationMs(System.currentTimeMillis() - start)
                    .terminalEgress(false)      // V2 marks the exit hop; V1 tags every hop
                    .enforcementMode("OBSERVE")
                    .actionTaken("OBSERVED")
                    .classifiedAt(LocalDateTime.now())
                    .build();

            repository.save(row);
            log.debug("Egress classified corr={} sensitivity={} categories={} injection={}",
                    ctx.correlationId(), d.sensitivity(), d.categories(), d.injectionDetected());
        } catch (Exception e) {
            // Fail-open: never break a working call for a tagging failure (the hop shows as "not scanned").
            log.warn("Egress classification failed for corr={} ({}): {}",
                    ctx.correlationId(), ctx.capabilityName(), e.getMessage());
        }
    }
}
