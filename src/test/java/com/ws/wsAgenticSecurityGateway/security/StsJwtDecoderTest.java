package com.ws.wsAgenticSecurityGateway.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.sts.service.StsKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StsJwtDecoder}: the gateway validates a JWT its own STS minted (the multi-hop OBO), by
 * the tenant named in the token's issuer, against that tenant's public keys — accepting a well-formed token
 * and rejecting expiry, a non-STS issuer, and an unknown/keyless tenant. A real RSA key signs the fixtures, so
 * the signature path is genuinely exercised.
 */
class StsJwtDecoderTest {

    private static final String ISSUER_BASE = "https://gateway.local";
    private static final String TENANT = "amitdev.local";
    private static final String ISSUER = ISSUER_BASE + "/sts/" + TENANT;

    private final StsKeyService keyService = mock(StsKeyService.class);
    private StsJwtDecoder decoder;
    private RSAKey signingKey;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("sts-kid-1").generate();
        when(keyService.jwks(TENANT)).thenReturn(new JWKSet(signingKey.toPublicJWK()));
        decoder = new StsJwtDecoder(keyService, ISSUER_BASE);
    }

    private String mintObo(String issuer, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("amit-prakash-root")
                .audience("billing")
                .expirationTime(Date.from(exp))
                .issueTime(Date.from(Instant.now()))
                .claim("ws_tenant", TENANT)
                .claim("act_chain", List.of(Map.of("id", "amit", "type", "human")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void handles_recognizesOnlyGatewayStsIssuer() {
        assertThat(decoder.handles(ISSUER)).isTrue();
        assertThat(decoder.handles("https://keycloak.example/realms/ws-gateway")).isFalse();
        assertThat(decoder.handles(null)).isFalse();
    }

    @Test
    void decode_validGatewayObo_returnsJwtWithClaims() throws Exception {
        String token = mintObo(ISSUER, Instant.now().plusSeconds(300));

        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(jwt.getSubject()).isEqualTo("amit-prakash-root");
        assertThat(jwt.getClaimAsString("ws_tenant")).isEqualTo(TENANT);
        assertThat(jwt.getAudience()).containsExactly("billing");
    }

    @Test
    void decode_expiredToken_throws() throws Exception {
        String token = mintObo(ISSUER, Instant.now().minusSeconds(120));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void decode_nonStsIssuer_throws() throws Exception {
        String token = mintObo(ISSUER_BASE + "/not-sts", Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("Not a gateway STS token");
    }

    @Test
    void decode_tenantWithNoKeys_throws() throws Exception {
        when(keyService.jwks("ghost.tenant")).thenReturn(new JWKSet());
        String token = mintObo(ISSUER_BASE + "/sts/ghost.tenant", Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("No STS verification keys");
    }
}
