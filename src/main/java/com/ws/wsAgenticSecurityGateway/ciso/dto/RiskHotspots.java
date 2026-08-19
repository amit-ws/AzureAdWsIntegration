package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.util.List;

/**
 * The CISO → Dashboard "Risk hotspots" widget (#72) — the entities with the highest combined sensitivity, privilege,
 * and behavioural risk, in three groups. Principals split into humans and NHIs (one widget, two tabs). Each entity
 * carries a 0–100 score and a band; the score inputs are all real (peak sensitivity reached, sensitive-event volume,
 * server fan-out, uncovered-sensitive exposure), while the score formula + band thresholds are ours and transparent.
 */
public record RiskHotspots(
        List<Hotspot> humans,
        List<Hotspot> nhis,
        List<Hotspot> agents,
        List<Hotspot> servers) {

    /**
     * One ranked entity. {@code band} ∈ CRITICAL | HIGH | MEDIUM | LOW (derived from {@code score}); {@code reason}
     * is the primary plain-English driver; {@code facts} are the real supporting numbers.
     */
    public record Hotspot(
            String id,
            String name,
            int score,
            String band,
            String reason,
            List<String> facts) {}
}
