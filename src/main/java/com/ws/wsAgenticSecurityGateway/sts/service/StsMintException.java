package com.ws.wsAgenticSecurityGateway.sts.service;

/**
 * Thrown when the STS cannot mint a token. The hop path treats this as a hard deny (fail-closed,
 * Locked Decision 4) — a hop must never proceed without a valid minted delegation token.
 */
public class StsMintException extends RuntimeException {

    public StsMintException(String message) {
        super(message);
    }

    public StsMintException(String message, Throwable cause) {
        super(message, cause);
    }
}
