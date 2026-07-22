package com.ws.wsAgenticSecurityGateway.sts.model;

import java.time.Instant;

/** Output of {@code StsService.mint} — the signed compact JWT plus metadata for audit/observability. */
public record MintedToken(String token, String jti, String kid, Instant expiresAt) {}
