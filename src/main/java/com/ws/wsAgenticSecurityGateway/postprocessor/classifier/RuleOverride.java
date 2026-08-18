package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.List;

/**
 * An admin override of a built-in detector's output: remap what a matcher tags and how high it lifts the ladder.
 * Applied by {@link EgressClassifier} to every recognition whose {@code matcher} equals the override's target.
 *
 * @param categories       replacement data-categories (one match is tagged with all of them), or {@code null}/empty
 *                         to keep the detector's own category
 * @param sensitivityFloor replacement ladder rank (see {@link Sensitivity})
 */
public record RuleOverride(List<String> categories, int sensitivityFloor) {
}
