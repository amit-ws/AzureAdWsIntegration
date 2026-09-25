package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.List;

/**
 * The pluggable detection seam. Given a response body, a recognizer returns zero or more {@link Recognition}s.
 * Deterministic and side-effect-free so the pipeline can run it safely on the hot path's shadow.
 *
 * <p>Built-ins ship in {@link BuiltInRecognizers}. Admin-defined rules (the rule engine) and future ML / exact-
 * data-match classifiers slot in as additional recognizers with no change to the pipeline — this interface is
 * the socket the heavier tiers plug into.
 */
@FunctionalInterface
public interface Recognizer {

    List<Recognition> find(String text);
}
