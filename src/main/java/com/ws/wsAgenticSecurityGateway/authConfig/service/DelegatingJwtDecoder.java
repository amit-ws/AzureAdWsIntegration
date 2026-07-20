package com.ws.wsAgenticSecurityGateway.authConfig.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DelegatingJwtDecoder implements JwtDecoder {

    private final AtomicReference<JwtDecoder> activeDecoder = new AtomicReference<>();
    private final AtomicReference<JwtDecoder> previousDecoder = new AtomicReference<>();
    private final AtomicReference<Instant> gracePeriodEnd = new AtomicReference<>();
    private final AtomicReference<String> previousIssuer = new AtomicReference<>();

    private volatile Runnable onGracePeriodEndCallback;

    public DelegatingJwtDecoder(JwtDecoder initialDecoder) {
        this.activeDecoder.set(initialDecoder);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Instant gpEnd = gracePeriodEnd.get();
        if (gpEnd != null && Instant.now().isBefore(gpEnd)) {
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
                        throw activeEx;
                    }
                }
                throw activeEx;
            }
        }

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

        JwtDecoder active = activeDecoder.get();
        if (active == null) {
            throw new JwtException("No JWT decoder configured — auth may be in mode=none");
        }
        return active.decode(token);
    }

    public void swapDecoder(JwtDecoder newDecoder, int gracePeriodMinutes, String oldIssuer) {
        JwtDecoder current = activeDecoder.get();
        previousDecoder.set(current);
        activeDecoder.set(newDecoder);
        previousIssuer.set(oldIssuer);
        gracePeriodEnd.set(Instant.now().plusSeconds(gracePeriodMinutes * 60L));
        log.info("JWT decoder swapped — grace period active for {} minutes (previousIssuer={})",
                gracePeriodMinutes, oldIssuer);
    }

    public void setDecoder(JwtDecoder decoder) {
        activeDecoder.set(decoder);
        previousDecoder.set(null);
        gracePeriodEnd.set(null);
        previousIssuer.set(null);
    }

    public boolean isGracePeriodActive() {
        Instant gpEnd = gracePeriodEnd.get();
        return gpEnd != null && Instant.now().isBefore(gpEnd);
    }

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
