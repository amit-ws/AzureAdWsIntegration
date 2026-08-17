package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

/**
 * An admin override of a built-in detector's output: remap what a matcher tags and how high it lifts the ladder.
 * Applied by {@link EgressClassifier} to every recognition whose {@code matcher} equals the override's target.
 *
 * @param category        replacement data-category, or {@code null}/blank to keep the detector's own category
 * @param sensitivityFloor replacement ladder rank (see {@link Sensitivity})
 */
public record RuleOverride(String category, int sensitivityFloor) {
}
