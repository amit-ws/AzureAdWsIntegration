package com.ws.wsAgenticSecurityGateway.sts.model;

import java.time.Instant;

/**
 * Output of {@code StsService.mint} — the signed compact JWT plus the full set of claims that were
 * minted, so the audit layer can record a complete token receipt (iss/sub/aud/scope/kid/alg/iat/exp)
 * without re-parsing — or ever logging — the raw JWT.
 *
 * <p>The {@code token} string is for the wire on internal, STS-trusting legs; it is deliberately never
 * written to the audit trail. {@code jti} is the safe, unique reference for correlation and revocation.
 */
public record MintedToken(
        String token,
        String jti,
        String kid,
        String alg,
        String issuer,
        String subject,
        String audience,
        String scope,
        Instant issuedAt,
        Instant expiresAt) {}
