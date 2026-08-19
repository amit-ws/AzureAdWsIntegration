package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The CISO → Dashboard "overview" payload — the top-of-page summary widgets, all from real governed-traffic data:
 * the executive KPI strip (#67), the posture scorecard (#68, reusing {@code PostureService}), the data-sensitivity
 * mix (#71), and policy/enforcement coverage (#75). Every value is a real count or a transparent ratio; nothing is
 * fabricated. Period-over-period deltas compare the current window to the immediately-preceding equal window.
 */
public record DashboardOverview(
        String tenant,
        LocalDateTime generatedAt,
        String window,
        List<Kpi> kpis,
        Posture posture,
        List<SensitivitySlice> sensitivityMix,
        long classifiedTotal,
        Coverage coverage) {

    /** One KPI tile. {@code previous} = the same metric over the prior equal window (null when no delta is computed). */
    public record Kpi(String id, String label, long value, Long previous, String hint) {}

    /** Executive posture, reusing the real graded posture scorecard. {@code coveredTrafficPct} = decisions decided by an explicit policy. */
    public record Posture(int score, String grade, String headline,
                          int critical, int warning, int good,
                          int coveredTrafficPct, List<PostureLine> lines) {}

    /** One posture narrative line (a scored check surfaced in plain English). */
    public record PostureLine(String title, String status, String detail, String why) {}

    /** One slice of the sensitivity donut. */
    public record SensitivitySlice(String sensitivity, long count, int pct) {}

    /**
     * Policy / enforcement coverage. {@code decisionsAttributedPct} = requests decided by an explicit policy (PDP);
     * the sensitive/server counts and {@code enforcementGaps} come from egress-policy coverage of classified data.
     */
    public record Coverage(int decisionsAttributedPct,
                           int sensitiveCaps, int coveredSensitiveCaps,
                           int servers, int coveredServers,
                           int enforcementGaps) {}
}
