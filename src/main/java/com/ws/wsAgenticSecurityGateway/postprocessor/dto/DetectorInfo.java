package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

/**
 * A built-in detector the admin UI can target with a DISABLE / OVERRIDE rule — its stable matcher id plus the
 * default category and sensitivity it ships with.
 */
public record DetectorInfo(
        String matcher,
        String category,
        String defaultSensitivity,
        String description) {
}
