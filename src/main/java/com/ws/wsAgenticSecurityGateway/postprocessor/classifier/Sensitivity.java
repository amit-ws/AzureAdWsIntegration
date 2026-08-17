package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

import java.util.Locale;

/**
 * The egress sensitivity ladder. The overall label a response carries is the max rank any counted recognition
 * reached. Kept as a tiny shared holder so both {@link EgressClassifier} and the recognizers agree on the ranks —
 * and public so the admin rule engine can map its label strings onto the same ranks.
 */
public final class Sensitivity {

    private Sensitivity() {
    }

    /** Index is the rank; the overall label is the max rank across counted recognitions. */
    public static final String[] RANKS = { "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED" };

    public static final int PUBLIC = 0;
    public static final int INTERNAL = 1;
    public static final int CONFIDENTIAL = 2;
    public static final int RESTRICTED = 3;

    /** The rank index for a label (case-insensitive); unknown/blank ⇒ PUBLIC. */
    public static int rankOf(String label) {
        if (label == null) {
            return PUBLIC;
        }
        String u = label.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < RANKS.length; i++) {
            if (RANKS[i].equals(u)) {
                return i;
            }
        }
        return PUBLIC;
    }

    /** The label for a rank index; out-of-range ⇒ PUBLIC. */
    public static String labelOf(int rank) {
        return rank >= 0 && rank < RANKS.length ? RANKS[rank] : "PUBLIC";
    }
}
