package com.ws.wsAgenticSecurityGateway.ciso.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Security-posture scorecard — a snapshot of how tightly governed the tenant's agent estate is <em>right now</em>.
 *
 * <p>Read for a <b>default-deny</b> gateway: an agent with no permit simply cannot act, so "few/disabled policies"
 * is <em>not</em> a weakness — it is tighter. The real risk is the opposite: too-<b>broad enabled permits</b>.
 * Each check yields GOOD / WARN / CRITICAL and contributes weighted points to a 0–100 score (a lean default-deny
 * setup scores high). Every number is real.
 */
public record PostureReport(
        String tenant,
        LocalDateTime generatedAt,
        int score,
        String grade,
        String headline,
        int criticalCount,
        int warningCount,
        int goodCount,
        List<PostureCheck> checks,
        List<String> notes) {

    /**
     * One posture check. {@code status} ∈ GOOD | WARN | CRITICAL for scored checks, or INFO for an unscored
     * informational signal (weight 0 — shown with its real number but it never pads or dents the score).
     * {@code reads} says whether it reflects current configuration ({@code "config"}) or observed activity
     * ({@code "observed"}). {@code detail} is the real number/summary behind it; {@code why} is a one-line
     * "what it means / what to do".
     */
    public record PostureCheck(
            String id,
            String title,
            String status,
            String reads,
            String detail,
            String why,
            int weight,
            int pointsEarned) {}
}
