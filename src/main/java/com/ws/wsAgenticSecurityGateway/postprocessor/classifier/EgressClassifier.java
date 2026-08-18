package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The built-in, deterministic egress classifier — the "cheap detectors inline" tier of the post-processor,
 * built as a pluggable recognizer pipeline with confidence scoring (Presidio / Cloud DLP style). Given a
 * response body it reports WHICH kinds of sensitive data it carries (categories), an overall sensitivity, which
 * detectors fired (non-sensitive evidence — counts + confidence, never the matched value), and whether
 * prompt-injection markers are present.
 *
 * <p>Pipeline: each {@link Recognizer} contributes {@link Recognition}s → a proximity/context boost adjusts
 * confidence → a confidence gate decides whether a finding is strong enough to count toward the sensitivity
 * ladder → survivors aggregate into a {@link DetectionResult}. Pure + side-effect-free, so it is trivially
 * unit-testable and safe to run on the hot path's shadow.
 *
 * <p>The recognizer list is the extension seam: the built-ins ship here; admin-defined rules (the rule engine)
 * and future ML / exact-data-match classifiers plug in as additional {@link Recognizer}s with no pipeline change.
 */
@Component
public class EgressClassifier {

    /** Post-context-boost confidence a finding needs to count toward categories + sensitivity. */
    private static final double GATE = 0.60;

    private final List<Recognizer> recognizers;

    public EgressClassifier() {
        this(BuiltInRecognizers.all());
    }

    /** The pluggable seam: run the built-ins plus any extra recognizers (admin rules / ML) supplied by the caller. */
    EgressClassifier(List<Recognizer> recognizers) {
        this.recognizers = List.copyOf(recognizers);
    }

    /** Classify with built-in recognizers only (no admin rules). Never returns null. */
    public DetectionResult classify(String text) {
        return classify(text, RulePolicy.empty());
    }

    /**
     * Classify a response body into categories + sensitivity + detector evidence, applying a tenant's admin
     * {@link RulePolicy} on top of the built-ins: extra custom recognizers run, disabled detectors are dropped,
     * and overridden detectors are remapped. Never returns null.
     */
    public DetectionResult classify(String text, RulePolicy policy) {
        if (text == null || text.isBlank()) {
            return DetectionResult.clean();
        }
        RulePolicy rules = policy == null ? RulePolicy.empty() : policy;

        List<Recognizer> active;
        if (rules.extraRecognizers().isEmpty()) {
            active = recognizers;
        } else {
            active = new ArrayList<>(recognizers);
            active.addAll(rules.extraRecognizers());
        }

        TreeSet<String> categories = new TreeSet<>();
        Map<String, Detector> detectorAgg = new LinkedHashMap<>();
        int rank = Sensitivity.PUBLIC;
        boolean injection = false;

        for (Recognizer recognizer : active) {
            List<Recognition> found;
            try {
                found = recognizer.find(text);
            } catch (Exception e) {
                continue; // a misbehaving recognizer never breaks classification
            }
            if (found == null) {
                continue;
            }
            for (Recognition rec : found) {
                String matcher = rec.matcher();
                if (rules.disabledMatchers().contains(matcher)) {
                    continue; // admin turned this detector off
                }
                if ("prompt_injection".equals(matcher)) {
                    injection = true;
                    detectorAgg.computeIfAbsent("prompt_injection", k -> new Detector("phrase"))
                            .add(rec.start(), rec.end(), 1.0);
                    continue; // injection is a flag, not a confidentiality category
                }
                int floor = rec.sensitivityFloor();
                List<String> overrideCategories = null; // non-null → replaces the detector's own category
                RuleOverride override = rules.overrides().get(matcher);
                if (override != null) {
                    if (override.categories() != null && !override.categories().isEmpty()) {
                        overrideCategories = override.categories();
                    }
                    floor = override.sensitivityFloor();
                }
                double confidence = ContextScorer.boosted(rec.confidence(), rec.contextKey(), text, rec.start(), rec.end());
                if (confidence < GATE) {
                    continue; // weak, uncorroborated signal — do not escalate (raw kept in audit for reprocess)
                }
                if (overrideCategories != null) {
                    categories.addAll(overrideCategories);
                } else {
                    categories.add(rec.category());
                }
                rank = Math.max(rank, floor);
                detectorAgg.computeIfAbsent(matcher, k -> new Detector(matcherType(matcher)))
                        .add(rec.start(), rec.end(), confidence);
            }
        }

        Map<String, Object> detectors = new LinkedHashMap<>();
        for (Map.Entry<String, Detector> e : detectorAgg.entrySet()) {
            detectors.put(e.getKey(), e.getValue().toMap());
        }
        return new DetectionResult(new ArrayList<>(categories), Sensitivity.RANKS[rank], detectors, injection);
    }

    private static String matcherType(String matcher) {
        return switch (matcher) {
            case "credit_card", "iban", "ssn" -> "checksum";
            case "high_entropy" -> "entropy";
            default -> "pattern";
        };
    }

    /** Aggregates one detector's distinct match spans + max confidence into the evidence map. */
    private static final class Detector {
        private final String type;
        private final Set<String> spans = new HashSet<>();
        private double maxConfidence;

        Detector(String type) {
            this.type = type;
        }

        void add(int start, int end, double confidence) {
            spans.add(start + ":" + end);
            maxConfidence = Math.max(maxConfidence, confidence);
        }

        Map<String, Object> toMap() {
            return Map.of(
                    "count", spans.size(),
                    "matcher", type,
                    "confidence", Math.round(maxConfidence * 100.0) / 100.0);
        }
    }
}
