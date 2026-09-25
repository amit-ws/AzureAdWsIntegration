package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.List;
import java.util.Map;

/**
 * The outcome of classifying one response body — metadata only, never the raw value.
 *
 * @param categories  detected data-category kinds (e.g. {@code ["PII","FINANCIAL","SECRET"]}), value-driven
 * @param sensitivity overall label: {@code PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED} (the max across detectors)
 * @param detectors   which detectors fired + non-sensitive evidence (e.g. {@code {"ssn":{"count":1,"matcher":"pattern"}}})
 * @param injectionDetected true if the response carries prompt-injection markers (V1 flags; V2 neutralizes)
 */
public record DetectionResult(
        List<String> categories,
        String sensitivity,
        Map<String, Object> detectors,
        boolean injectionDetected) {

    /** Nothing sensitive found — the clean/PUBLIC result. */
    public static DetectionResult clean() {
        return new DetectionResult(List.of(), "PUBLIC", Map.of(), false);
    }
}
