package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One producer→consumer edge in the sensitive-data-sharing map: how much (and how sensitive) data flowed from
 * one tool/agent to another. Answers "which agents share sensitive data, and with whom."
 */
public record SharingEdge(
        String producer,
        String consumer,
        long total,
        long sensitiveCount,
        String peakSensitivity,
        Map<String, Long> bySensitivity,
        LocalDateTime lastSeen) {
}
