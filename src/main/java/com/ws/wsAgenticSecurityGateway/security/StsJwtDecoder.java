package com.ws.wsAgenticSecurityGateway.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.text.ParseException;

/**
 * Validates a JWT the gateway's <em>own</em> STS minted — the per-hop OBO a downstream agent forwards back to
 * the gateway on a multi-hop A2A leg (e.g. {@code caller → gateway → billing → gateway → refund}). Without
 * this, the second hop's inbound token ({@code iss = <issuer-base>/sts/<tenant>}, signed by the gateway's
 * rotating STS keys) is rejected by the IdP decoder, and the delegation chain can't extend past the first hop.
 *
 * <p>The token's {@code iss} names the tenant; the tenant's public verification keys come from
 * {@link StsKeyService#jwks} (ACTIVE + RETIRING, so a token minted just before a key rotation still verifies).
 * Signature, issuer, and expiry are all checked. Cross-tenant forgery is impossible — a token is only ever
 * verified against the keys of the tenant named in its own issuer.
 */
@Component
public class StsJwtDecoder implements JwtDecoder {

    private static final String STS_PATH = "/sts/";

    private final StsKeyService keyService;
    private final String issuerBase;

    public StsJwtDecoder(StsKeyService keyService,
                         @Value("${ws.gateway.sts.issuer-base:https://gateway.local}") String issuerBase) {
        this.keyService = keyService;
        this.issuerBase = issuerBase;
    }

    /** Whether a token with this {@code iss} was issued by the gateway's own STS (routing predicate). */
    public boolean handles(String issuer) {
        return issuer != null && issuer.startsWith(issuerBase + STS_PATH);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("Malformed STS token: " + e.getMessage(), e);
        }
        if (!handles(issuer)) {
            throw new BadJwtException("Not a gateway STS token (iss=" + issuer + ")");
        }
        String tenant = issuer.substring((issuerBase + STS_PATH).length());
        JWKSet jwks = keyService.jwks(tenant);
        if (jwks == null || jwks.getKeys().isEmpty()) {
            throw new BadJwtException("No STS verification keys for tenant '" + tenant + "'");
        }
        return buildDecoder(jwks, issuer).decode(token);
    }

    /** A Nimbus decoder pinned to this tenant's in-memory STS public keys, validating signature + iss + expiry. */
    private NimbusJwtDecoder buildDecoder(JWKSet jwks, String issuer) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(jwks);
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
