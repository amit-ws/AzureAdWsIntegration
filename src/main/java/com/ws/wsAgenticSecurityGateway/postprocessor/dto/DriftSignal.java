package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

/**
 * A drift alert: a capability whose sensitivity ESCALATED in the recent window versus its earlier baseline —
 * e.g. a tool that used to return PUBLIC now returns RESTRICTED. A possible leak, misconfiguration, or compromise.
 *
 * @param producer       the tool's server / the agent
 * @param capabilityName the capability that drifted
 * @param baselinePeak   the highest sensitivity seen before the window (its "normal")
 * @param recentPeak     the highest sensitivity seen in the recent window (now higher)
 * @param recentCount    how many recent responses were at the elevated level
 */
public record DriftSignal(
        String producer,
        String capabilityName,
        String baselinePeak,
        String recentPeak,
        long recentCount) {
}
