package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.MintRequest;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import com.ws.wsAgenticSecurityGateway.sts.model.OboInvariants;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The gateway's Security Token Service: mints short-lived, scoped, {@code act_chain}-carrying OBO
 * tokens (RS256) with the tenant's active signing key.
 *
 * <p><b>Fail-closed (Locked Decision 4):</b> any failure — key unavailable or signing error — throws
 * {@link StsMintException}; the caller must deny the hop. This is deliberately NOT the codebase's
 * fail-open PDP posture.
 */
@Service
@Slf4j
public class StsService {

    private static final long DEFAULT_TTL_SECONDS = 120;

    private final StsKeyService keyService;
    private final String issuerBase;

    public StsService(StsKeyService keyService,
                      @Value("${ws.gateway.sts.issuer-base:https://gateway.local}") String issuerBase) {
        this.keyService = keyService;
        this.issuerBase = issuerBase;
    }

    /** Mint a per-hop OBO token. Fail-closed — throws {@link StsMintException} on any failure. */
    public MintedToken mint(MintRequest req) {
        RSAKey signingKey;
        try {
            signingKey = keyService.activeSigningKey(req.tenant());
        } catch (Exception e) {
            throw new StsMintException("STS signing key unavailable for tenant " + req.tenant(), e);
        }

        Instant now = Instant.now();
        long ttl = req.ttlSeconds() > 0 ? req.ttlSeconds() : DEFAULT_TTL_SECONDS;
        Instant exp = now.plusSeconds(ttl);
        String jti = UUID.randomUUID().toString();

        ActChain chain = req.actChain();
        Principal root = chain != null ? chain.root() : null;

        // Hardening 10: codified OBO delegation-chain invariants. The result is embedded in the token below
        // (obo_invariants claim) so the audit receipt is faithful to the token it represents. Fail-closed when a
        // present chain is structurally corrupt (roles out of order) — such a token must never be minted.
        // all-verified / scope stay reported (the PDP lineage guardrails own the deny for an unverified chain).
        OboInvariants.Result invariants = OboInvariants.check(null, chain, req.scope());
        if (chain != null && !chain.isEmpty() && !invariants.structurallyValid()) {
            throw new StsMintException("OBO token invariants violated (fail-closed): " + invariants.violations());
        }

        String issuer = issuerBase + "/sts/" + req.tenant();
        String subject = root != null ? root.id() : "unknown";

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(req.targetServer())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .jwtID(jti)
                .claim("act_chain", chain != null ? chain.toClaim() : List.of())
                .claim("scope", req.scope())
                .claim("trace_id", req.traceId())
                .claim("corr_id", req.correlationId())
                .claim("ws_tenant", req.tenant())
                .claim("obo_invariants", invariants.toClaim());
        Map<String, Object> actClaim = chain != null ? chain.toActClaim() : null;
        if (actClaim != null) {
            claims.claim("act", actClaim); // RFC 8693 nested actor claim (standards interop)
        }

        // Sender-constraint (cnf): bind an agent-to-agent OBO to its RECIPIENT — the target agent (the token's
        // audience) that receives it and presents it back on its next hop. At honor time the presenter must
        // prove that same identity (its X-Agent-Assertion), so a stolen/leaked bearer OBO is useless. Only A2A
        // hops hand a token to an agent that presents it back; an MCP-scoped token is spent by the gateway on an
        // external server that never presents it, so it carries no cnf.
        if (req.scope() != null && req.scope().startsWith("a2a:")
                && req.targetServer() != null && !req.targetServer().isBlank()) {
            claims.claim("cnf", Map.of("workload_id", req.targetServer()));
        }

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            log.debug("Minted STS OBO token: jti={}, tenant={}, aud={}, scope={}, exp={}",
                    jti, req.tenant(), req.targetServer(), req.scope(), exp);
            return new MintedToken(jwt.serialize(), jti, signingKey.getKeyID(),
                    JWSAlgorithm.RS256.getName(), issuer, subject, req.targetServer(),
                    req.scope(), now, exp);
        } catch (JOSEException e) {
            throw new StsMintException("Failed to sign STS OBO token for tenant " + req.tenant(), e);
        }
    }
}
