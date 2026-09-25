package com.ws.wsAgenticSecurityGateway.compliance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The <b>structure</b> of a compliance report, supplied as data (a shipped default, or uploaded by an admin as
 * JSON/YAML). The backend fills each evidence slot from its metric "menu" — the template chooses the layout and
 * which metric goes where, but can only reference metrics the gateway actually computes (see {@code ComplianceService}).
 *
 * <p>Unknown JSON/YAML fields are ignored so templates can carry extra annotations without breaking parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComplianceTemplate(
        String framework,
        String disclaimer,
        List<TemplateControl> controls) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateControl(
            String ref,
            String title,
            String requirement,
            String howSatisfied,
            List<TemplateEvidence> evidence) {}

    /** One evidence line: a human label and the {@code metric} key it pulls its value from (e.g. {@code decisions.denied}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TemplateEvidence(String label, String metric) {}
}
