package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

/**
 * One raw detection produced by a {@link Recognizer}: what kind of sensitive data, how high it lifts the
 * sensitivity ladder, the recognizer's base confidence, a stable detector id, and the match span (so the
 * pipeline can apply proximity/context scoring). Carries NO raw matched value — metadata only, by design, so
 * secret values never propagate past the recognizer.
 *
 * @param category         data-category kind (e.g. {@code PII}, {@code FINANCIAL}, {@code SECRET}, {@code NETWORK})
 * @param sensitivityFloor ladder index this recognition lifts to (see {@link Sensitivity})
 * @param confidence       base confidence in [0,1] for this recognizer class (pre context-boost)
 * @param matcher          stable detector id (e.g. {@code email}, {@code ssn}, {@code credit_card}, {@code iban})
 * @param contextKey       category key for proximity keyword lookup, or {@code null} to skip context boosting
 * @param start            match start offset in the scanned text (for context scoring)
 * @param end              match end offset, exclusive
 */
public record Recognition(
        String category,
        int sensitivityFloor,
        double confidence,
        String matcher,
        String contextKey,
        int start,
        int end) {
}
