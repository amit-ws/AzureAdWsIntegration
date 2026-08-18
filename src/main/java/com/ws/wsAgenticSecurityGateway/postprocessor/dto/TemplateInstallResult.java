package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.util.List;

/**
 * Outcome of installing a whole template pack: how many were newly added, how many were already present, and any
 * templates skipped because their name collides with a rule the tenant already authored by hand.
 */
public record TemplateInstallResult(
        int installed,
        int alreadyInstalled,
        List<String> conflicts) {
}
