package com.ws.wsAgenticSecurityGateway.postprocessor.dto;

import java.util.List;
import java.util.UUID;

/**
 * A built-in detector as it effectively applies for one tenant right now: its shipped defaults plus whatever the
 * tenant's admin rules have done to it. A built-in can be turned off (a {@code DISABLE} rule) or remapped (an
 * {@code OVERRIDE} rule) — but never deleted, because its detection logic is code. The rule ids let the admin UI
 * toggle / clear those adjustments without knowing the underlying rule mechanics.
 *
 * @param matcher             stable built-in id (e.g. {@code high_entropy})
 * @param description         human description of what it detects
 * @param defaultCategory     the category it ships with
 * @param defaultSensitivity  the sensitivity it ships with
 * @param enabled             false when an enabled DISABLE rule is silencing it
 * @param disableRuleId       the DISABLE rule silencing it, or null
 * @param overrideCategories  the OVERRIDE rule's replacement categories, or null when not overridden
 * @param overrideSensitivity the OVERRIDE rule's replacement sensitivity, or null when not overridden
 * @param overrideRuleId      the OVERRIDE rule remapping it, or null
 */
public record EffectiveDetectorView(
        String matcher,
        String description,
        String defaultCategory,
        String defaultSensitivity,
        boolean enabled,
        UUID disableRuleId,
        List<String> overrideCategories,
        String overrideSensitivity,
        UUID overrideRuleId) {
}
