package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The learned data profile of one capability (a tool or skill), keyed by who produces it — what kind of data it
 * typically returns. Powers "which tools/agents handle sensitive data" and the auto-suggest for rules/policy.
 *
 * @param producer        the tool's server / the agent that returns the data
 * @param capabilityName  the capability's public name
 * @param capabilityType  TOOL / SKILL / PROMPT / RESOURCE
 * @param protocol        MCP / A2A
 * @param total           total classified responses observed for this capability
 * @param sensitiveCount  how many were above PUBLIC (INTERNAL/CONFIDENTIAL/RESTRICTED)
 * @param peakSensitivity the highest sensitivity ever seen for this capability
 * @param bySensitivity   count per sensitivity label
 * @param lastSeen        most recent classification time
 */
public record CapabilityFingerprint(
        String producer,
        String capabilityName,
        String capabilityType,
        String protocol,
        long total,
        long sensitiveCount,
        String peakSensitivity,
        Map<String, Long> bySensitivity,
        LocalDateTime lastSeen) {
}
