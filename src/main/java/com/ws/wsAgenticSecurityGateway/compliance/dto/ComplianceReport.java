package com.ws.wsAgenticSecurityGateway.compliance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A compliance evidence pack for a named framework (SOC 2 today; GDPR / EU AI Act to follow). It maps the
 * gateway's real audit / PDP / identity / policy data onto specific controls. It is <b>evidence for the listed
 * controls, not a certification</b> — see {@link #disclaimer()}. Every value comes from real data (see
 * {@code ComplianceService}); nothing is fabricated.
 *
 * <p>Self-contained in the {@code compliance} package so the module ships independently of the CISO dashboard.
 */
public record ComplianceReport(
        String framework,
        String tenant,
        LocalDateTime generatedAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        String disclaimer,
        List<ControlEvidence> controls) {

    /** One control and the real evidence gathered for it. */
    public record ControlEvidence(
            String ref,
            String title,
            String requirement,
            String howSatisfied,
            String status,               // EVIDENCED | PARTIAL | NO_DATA
            List<EvidenceItem> evidence) {}

    /** A single evidence data point — a human-readable label and its value pulled from the gateway. */
    public record EvidenceItem(String label, String value) {}
}
