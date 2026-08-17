package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The egress Insights bundle — analytics derived live from the classification table (nothing stored, nothing
 * fabricated): per-capability fingerprints, the sensitive-data-sharing map, and drift alerts.
 */
public record InsightsReport(
        List<CapabilityFingerprint> fingerprints,
        List<SharingEdge> sharingEdges,
        List<DriftSignal> drift,
        LocalDateTime generatedAt) {
}
