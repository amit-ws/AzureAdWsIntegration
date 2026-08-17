package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

/**
 * The peak sensitivity of one skill of an agent — keyed by the producing agent's id + the skill (capability)
 * name (skills have no separate registry table, so agentId + name + tenant is the stable key).
 */
public record SkillSensitivity(
        String producerAgentId,
        String agentName,
        String capabilityName,
        String peakSensitivity) {
}
