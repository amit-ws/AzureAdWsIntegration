package com.ws.wsAgenticSecurityGateway.sts.model;

/**
 * Input to {@code StsService.mint} — the per-hop OBO mint contract. The root {@code sub} and the
 * {@code actor} claim are derived from {@link #actChain()} (root / actor).
 */
public record MintRequest(
        String tenant,
        ActChain actChain,
        String targetServer,
        String scope,
        String correlationId,
        long ttlSeconds
) {}
