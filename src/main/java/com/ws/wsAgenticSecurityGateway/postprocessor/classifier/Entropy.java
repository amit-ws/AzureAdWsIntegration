package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shannon-entropy helpers for high-entropy secret detection (gitleaks / trufflehog style). Entropy is measured
 * per token and normalized by the token's charset so a single threshold works for both base64 and hex.
 */
final class Entropy {

    private Entropy() {
    }

    /** Shannon entropy in bits/char over the token's character-frequency distribution. */
    static double shannon(String s) {
        if (s == null || s.isEmpty()) {
            return 0.0;
        }
        Map<Character, Integer> freq = new HashMap<>();
        for (int i = 0; i < s.length(); i++) {
            freq.merge(s.charAt(i), 1, Integer::sum);
        }
        double h = 0.0;
        double n = s.length();
        for (int f : freq.values()) {
            double p = f / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    /**
     * Normalized entropy ratio {@code H / log2(charsetSize)} where charsetSize is 16 (hex) or 64 (base64/other).
     * A ratio ≥ 0.75 marks a likely secret for both charsets (4.5/6 and 3.0/4 both equal 0.75).
     */
    static double ratio(String token) {
        if (token == null || token.isEmpty()) {
            return 0.0;
        }
        int charsetSize = isHex(token) ? 16 : 64;
        double denom = Math.log(charsetSize) / Math.log(2);
        return shannon(token) / denom;
    }

    /** True if every character is a hex digit — lets {@link #ratio} normalize by 16 instead of 64. */
    private static boolean isHex(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Pre-filters that keep entropy high-precision: min length 20, no whitespace, enough character-class variety
     * to look random (≥3 of {lower,upper,digit,symbol}, or at least letter+digit), and enough distinct chars.
     * These strip long normal words / slugs before the entropy test runs; genuinely random secrets sail through.
     */
    static boolean looksRandom(String token) {
        if (token == null || token.length() < 20) {
            return false;
        }
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        Set<Character> distinct = new HashSet<>();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isWhitespace(c)) {
                return false;
            }
            distinct.add(c);
            if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                symbol = true;
            }
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
        boolean variety = classes >= 3 || ((lower || upper) && digit);
        return variety && distinct.size() >= 12;
    }
}
