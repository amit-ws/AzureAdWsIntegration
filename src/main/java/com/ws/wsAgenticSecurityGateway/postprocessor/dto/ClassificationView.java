package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Processed-Data view of one egress classification — the read shape the dashboard renders. Metadata only:
 * WHAT kind of data a response carried and which detectors fired, never the raw value (the store is not a
 * honeypot). {@code correlationId}/{@code traceId} are the jump keys from an audit event or a chain/trace view.
 */
public record ClassificationView(
        UUID id,
        String correlationId,
        String traceId,
        String protocol,
        String capabilityType,
        String capabilityName,
        String producer,
        String consumer,
        List<String> dataCategories,
        String sensitivity,
        boolean injectionDetected,
        Map<String, Object> detectors,
        Long volumeBytes,
        String status,
        String classifierVersion,
        Long durationMs,
        LocalDateTime classifiedAt) {

    public static ClassificationView from(GatewayResponseClassificationEntity e) {
        return new ClassificationView(
                e.getId(),
                e.getCorrelationId(),
                e.getTraceId(),
                e.getProtocol(),
                e.getCapabilityType(),
                e.getCapabilityName(),
                e.getProducer(),
                e.getConsumer(),
                e.getDataCategories(),
                e.getSensitivity(),
                e.isInjectionDetected(),
                e.getDetectors(),
                e.getVolumeBytes(),
                e.getStatus(),
                e.getClassifierVersion(),
                e.getDurationMs(),
                e.getClassifiedAt());
    }
}
