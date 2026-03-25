package com.ws.wsAgenticSecurityGateway.authConfig.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic JwtDecoder that supports runtime hot-swapping with a grace period.
 *
 * <p>During a grace period (e.g., when the admin changes the IdP), this decoder
 * tries the new decoder first, then falls back to the old decoder. After the grace
 * period expires, the old decoder is dropped.
 *
 * <p>This enables IdP changes without restarting the gateway and without breaking
 * existing sessions whose JWTs were signed by the old IdP.
 */
@Slf4j
public class DelegatingJwtDecoder implements JwtDecoder {

    private final AtomicReference<JwtDecoder> activeDecoder = new AtomicReference<>();
    private final AtomicReference<JwtDecoder> previousDecoder = new AtomicReference<>();
    private final AtomicReference<Instant> gracePeriodEnd = new AtomicReference<>();
    private final AtomicReference<String> previousIssuer = new AtomicReference<>();

    /** Callback for audit events (set externally to avoid circular dependency). */
    private volatile Runnable onGracePeriodEndCallback;

    public DelegatingJwtDecoder(JwtDecoder initialDecoder) {
        this.activeDecoder.set(initialDecoder);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        // Check if grace period is active
        Instant gpEnd = gracePeriodEnd.get();
        if (gpEnd != null && Instant.now().isBefore(gpEnd)) {
            // Grace period active — try new decoder first, fall back to old
            JwtDecoder active = activeDecoder.get();
            JwtDecoder previous = previousDecoder.get();

            try {
                return active.decode(token);
            } catch (JwtException activeEx) {
                if (previous != null) {
                    log.debug("Active decoder failed during grace period, trying previous decoder");
                    try {
                        return previous.decode(token);
                    } catch (JwtException previousEx) {
                        log.debug("Both decoders failed during grace period");
                        throw activeEx; // throw the active decoder's error
                    }
                }
                throw activeEx;
            }
        }

        // Grace period expired — clean up previous decoder if still set
        if (gpEnd != null && !Instant.now().isBefore(gpEnd)) {
            JwtDecoder prev = previousDecoder.getAndSet(null);
            if (prev != null) {
                gracePeriodEnd.set(null);
                String prevIssuer = previousIssuer.getAndSet(null);
                log.info("Auth grace period ended — previous decoder dropped (previousIssuer={})", prevIssuer);
                if (onGracePeriodEndCallback != null) {
                    try {
                        onGracePeriodEndCallback.run();
                    } catch (Exception e) {
                        log.warn("Grace period end callback failed: {}", e.getMessage());
                    }
                }
            }
        }

        // Normal path — delegate to active decoder
        JwtDecoder active = activeDecoder.get();
        if (active == null) {
            throw new JwtException("No JWT decoder configured — auth may be in mode=none");
        }
        return active.decode(token);
    }

    /**
     * Swap to a new decoder with a grace period for the old one.
     * During the grace period, tokens signed by either IdP are accepted.
     */
    public void swapDecoder(JwtDecoder newDecoder, int gracePeriodMinutes, String oldIssuer) {
        JwtDecoder current = activeDecoder.get();
        previousDecoder.set(current);
        activeDecoder.set(newDecoder);
        previousIssuer.set(oldIssuer);
        gracePeriodEnd.set(Instant.now().plusSeconds(gracePeriodMinutes * 60L));
        log.info("JWT decoder swapped — grace period active for {} minutes (previousIssuer={})",
                gracePeriodMinutes, oldIssuer);
    }

    /**
     * Set decoder immediately without grace period.
     * Used for mode switches (none→oauth2, oauth2→none).
     */
    public void setDecoder(JwtDecoder decoder) {
        activeDecoder.set(decoder);
        previousDecoder.set(null);
        gracePeriodEnd.set(null);
        previousIssuer.set(null);
    }

    /** Returns true if the dual-decoder grace period is currently active. */
    public boolean isGracePeriodActive() {
        Instant gpEnd = gracePeriodEnd.get();
        return gpEnd != null && Instant.now().isBefore(gpEnd);
    }

    /** Returns remaining grace period minutes, or null if not active. */
    public Integer getGracePeriodRemainingMinutes() {
        Instant gpEnd = gracePeriodEnd.get();
        if (gpEnd == null || !Instant.now().isBefore(gpEnd)) return null;
        long remainingSeconds = gpEnd.getEpochSecond() - Instant.now().getEpochSecond();
        return (int) Math.ceil(remainingSeconds / 60.0);
    }

    public String getPreviousIssuer() {
        return previousIssuer.get();
    }

    public void setOnGracePeriodEndCallback(Runnable callback) {
        this.onGracePeriodEndCallback = callback;
    }
}
