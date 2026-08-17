package com.ws.wsAgenticSecurityGateway.postprocessor.classifier;

/**
 * The egress sensitivity ladder. The overall label a response carries is the max rank any counted recognition
 * reached. Kept as a tiny shared holder so both {@link EgressClassifier} and the recognizers agree on the ranks.
 */
final class Sensitivity {

    private Sensitivity() {
    }

    /** Index is the rank; the overall label is the max rank across counted recognitions. */
    static final String[] RANKS = { "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED" };

    static final int PUBLIC = 0;
    static final int INTERNAL = 1;
    static final int CONFIDENTIAL = 2;
    static final int RESTRICTED = 3;
}
