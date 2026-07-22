package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.sts.model.ActChain;
import com.ws.wsAgenticSecurityGateway.sts.model.MintRequest;
import com.ws.wsAgenticSecurityGateway.sts.model.MintedToken;
import com.ws.wsAgenticSecurityGateway.sts.model.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StsService} — real Nimbus signing key, mocked key service. Proves the minted
 * token carries the locked claim set, verifies against the signing key, and that mint is fail-closed.
 */
class StsServiceTest {

    private final StsKeyService keyService = mock(StsKeyService.class);
    private StsService sts;
    private RSAKey signingKey;
    private final UUID agentUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("kid-1").generate();
        sts = new StsService(keyService, "https://gateway.local");
    }

    private MintRequest req() {
        ActChain chain = new ActChain(List.of(
                Principal.human("sarah@acme.com", "sarah", "https://kc", true),
                Principal.agent(agentUuid.toString(), "agent-client", true)));
        return new MintRequest("acme", chain, "github",
                "mcp:tool:github:github_get_me", "corr-1", 120);
    }

    @Test
    void mint_producesLockedClaims_thatVerifyAgainstSigningKey() throws Exception {
        when(keyService.activeSigningKey("acme")).thenReturn(signingKey);

        MintedToken minted = sts.mint(req());
        assertThat(minted.jti()).isNotBlank();
        assertThat(minted.kid()).isEqualTo("kid-1");
        assertThat(minted.expiresAt()).isAfter(Instant.now());

        SignedJWT jwt = SignedJWT.parse(minted.token());
        assertThat(jwt.verify(new RSASSAVerifier(signingKey.toPublicJWK()))).isTrue();
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("kid-1");

        JWTClaimsSet c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo("https://gateway.local/sts/acme");
        assertThat(c.getSubject()).isEqualTo("sarah@acme.com");
        assertThat(c.getAudience()).containsExactly("github");
        assertThat(c.getJWTID()).isEqualTo(minted.jti());
        assertThat(c.getStringClaim("scope")).isEqualTo("mcp:tool:github:github_get_me");
        assertThat(c.getStringClaim("trace_id")).isEqualTo("corr-1");
        assertThat(c.getStringClaim("ws_tenant")).isEqualTo("acme");
        assertThat(c.getExpirationTime()).isNotNull();

        List<?> actChain = (List<?>) c.getClaim("act_chain");
        assertThat(actChain).hasSize(2);

        Map<String, Object> actor = c.getJSONObjectClaim("actor");
        assertThat(actor).containsEntry("id", agentUuid.toString()).containsEntry("type", "agent");
    }

    @Test
    void mint_isFailClosed_whenSigningKeyUnavailable() {
        when(keyService.activeSigningKey("acme")).thenThrow(new IllegalStateException("no key"));
        assertThatThrownBy(() -> sts.mint(req())).isInstanceOf(StsMintException.class);
    }
}
