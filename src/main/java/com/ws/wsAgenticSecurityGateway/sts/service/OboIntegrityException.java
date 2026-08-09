package com.ws.wsAgenticSecurityGateway.sts.service;

/**
 * Thrown when a hop's OBO delegation chain violates a structural integrity invariant (prefix-preserved,
 * append-only, sub-constant, monotonic roles). Fail-closed — the hop must be denied rather than mint/forward a
 * token on a corrupted lineage. In normal operation this never fires (the gateway builds the chain by appending
 * to the verified inbound one); if it does, it signals a chain-building bug or a tampered inbound token.
 */
public class OboIntegrityException extends RuntimeException {
    public OboIntegrityException(String message) {
        super(message);
    }
}
