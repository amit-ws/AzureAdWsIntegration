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
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
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
        Principal actor = chain != null ? chain.actor() : null;

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuerBase + "/sts/" + req.tenant())
                .subject(root != null ? root.id() : "unknown")
                .audience(req.targetServer())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .jwtID(jti)
                .claim("act_chain", chain != null ? chain.toClaim() : List.of())
                .claim("scope", req.scope())
                .claim("trace_id", req.correlationId())
                .claim("ws_tenant", req.tenant());
        if (actor != null) {
            claims.claim("actor", actor.toClaim());
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
            return new MintedToken(jwt.serialize(), jti, signingKey.getKeyID(), exp);
        } catch (JOSEException e) {
            throw new StsMintException("Failed to sign STS OBO token for tenant " + req.tenant(), e);
        }
    }
}
