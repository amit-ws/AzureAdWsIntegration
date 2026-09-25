package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Presidio-style proximity confidence boost. When a category keyword (e.g. "ssn", "card", "password") appears
 * within a small window of a match, that match's confidence is raised once by a fixed boost. This lifts weak
 * recognizers (a bare number, a high-entropy token) over the action threshold only when the surrounding text
 * corroborates them — the standard way DLP cuts false positives without missing labelled data.
 */
final class ContextScorer {

    private ContextScorer() {
    }

    /** ±32 chars ≈ ±5 tokens — Presidio's word-window equivalent. */
    private static final int WINDOW = 32;
    /** Presidio {@code context_similarity_factor}. */
    private static final double BOOST = 0.35;

    /** Category key -> word-boundary keyword pattern searched near a match of that category. */
    private static final Map<String, Pattern> KEYWORDS = Map.of(
            "ssn", Pattern.compile("(?i)\\b(ssn|social\\s+security|social\\s+sec|tin)\\b"),
            "card", Pattern.compile("(?i)\\b(card|cc|credit|debit|cvv|cvc|pan|expir)\\w*"),
            "secret", Pattern.compile("(?i)\\b(password|passwd|pwd|secret|api[ _-]?key|token|bearer|auth|"
                    + "credential|access[ _-]?key|private[ _-]?key)\\b"),
            "phone", Pattern.compile("(?i)\\b(phone|tel|mobile|cell|call)\\b"),
            "iban", Pattern.compile("(?i)\\b(iban|account|acct|routing|swift|bic)\\b"));

    /** Confidence after a single context boost if a matching keyword sits within the window; clamped to 1.0. */
    static double boosted(double base, String contextKey, String text, int start, int end) {
        if (contextKey == null) {
            return base;
        }
        Pattern kw = KEYWORDS.get(contextKey);
        if (kw == null) {
            return base;
        }
        int from = Math.max(0, start - WINDOW);
        int to = Math.min(text.length(), end + WINDOW);
        String window = text.substring(from, to);
        if (kw.matcher(window).find()) {
            return Math.min(1.0, base + BOOST);
        }
        return base;
    }
}
