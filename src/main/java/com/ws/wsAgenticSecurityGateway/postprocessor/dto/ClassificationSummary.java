package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.util.Map;

/**
 * Header counts for the Processed-Data view — computed SQL-side so they are accurate rather than derived from a
 * capped list.
 *
 * @param total          all classified response hops for the tenant
 * @param bySensitivity  count per sensitivity label (PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED)
 * @param injectionCount responses that carried prompt-injection markers
 */
public record ClassificationSummary(
        long total,
        Map<String, Long> bySensitivity,
        long injectionCount) {
}
