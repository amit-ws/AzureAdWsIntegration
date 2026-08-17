package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tenant's admin-defined adjustments layered on top of the built-in recognizers, resolved once per classify:
 * extra custom recognizers to run, built-in detectors to disable, and per-detector overrides. Built by the rule
 * engine from the {@code data_tag_rule} table; {@link #empty()} means "built-ins only" (the default).
 *
 * @param extraRecognizers custom recognizers compiled from admin CUSTOM rules
 * @param disabledMatchers built-in matcher ids the admin turned off (their recognitions are dropped)
 * @param overrides        built-in matcher id → {@link RuleOverride} (category/sensitivity remap)
 */
public record RulePolicy(
        List<Recognizer> extraRecognizers,
        Set<String> disabledMatchers,
        Map<String, RuleOverride> overrides) {

    private static final RulePolicy EMPTY = new RulePolicy(List.of(), Set.of(), Map.of());

    /** Built-ins only — no admin adjustments. */
    public static RulePolicy empty() {
        return EMPTY;
    }
}
