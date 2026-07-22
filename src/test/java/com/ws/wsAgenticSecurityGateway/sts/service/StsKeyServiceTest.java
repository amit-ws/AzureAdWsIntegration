package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.wsClient.service.ServerConfigCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StsKeyService} — real Nimbus crypto, mocked repository (backed by an
 * in-memory store) and pass-through crypto. Proves: a per-tenant key is generated + persisted,
 * and a token signed with the private key verifies against the PUBLIC jwk served by jwks().
 */
class StsKeyServiceTest {

    private final GatewayStsKeyRepository repo = mock(GatewayStsKeyRepository.class);
    private final ServerConfigCryptoService crypto = mock(ServerConfigCryptoService.class);
    private final List<GatewayStsKeyEntity> store = new ArrayList<>();

    private StsKeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new StsKeyService(repo, crypto);

        // crypto is a pass-through in the test (no real key material needed)
        when(crypto.encrypt(anyString())).thenAnswer(returnsFirstArg());
        when(crypto.decryptIfEncrypted(anyString())).thenAnswer(returnsFirstArg());

        // simulate persistence via the in-memory store
        when(repo.save(any())).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findFirstByWsTenantNameAndStatus(eq("acme"), eq("ACTIVE")))
                .thenAnswer(inv -> store.stream()
                        .filter(k -> "acme".equals(k.getWsTenantName()) && "ACTIVE".equals(k.getStatus()))
                        .findFirst());
        when(repo.findByWsTenantNameAndStatusIn(eq("acme"), any()))
                .thenAnswer(inv -> store.stream()
                        .filter(k -> "acme".equals(k.getWsTenantName()))
                        .toList());
    }

    @Test
    void generatesActiveKeyPerTenant_andSignedTokenVerifiesAgainstItsPublicJwk() throws Exception {
        RSAKey signingKey = keyService.activeSigningKey("acme");

        assertThat(signingKey.getKeyID()).isNotBlank();
        assertThat(signingKey.isPrivate()).isTrue();
        verify(repo).save(any());

        // sign a JWT with the private signing key
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                new JWTClaimsSet.Builder().subject("someone").build());
        jwt.sign(new RSASSASigner(signingKey));

        // verify with the PUBLIC jwk exposed via jwks() — proves the JWKS round-trip
        JWKSet publicSet = keyService.jwks("acme");
        RSAKey pub = (RSAKey) publicSet.getKeyByKeyId(signingKey.getKeyID());
        assertThat(pub).isNotNull();
        assertThat(pub.isPrivate()).isFalse();
        assertThat(jwt.verify(new RSASSAVerifier(pub))).isTrue();
    }

    @Test
    void reusesExistingActiveKey_doesNotRegenerate() {
        RSAKey first = keyService.activeSigningKey("acme");

        // a fresh service (empty in-memory cache) must reload the SAME key from the store, not mint a new one
        StsKeyService reloaded = new StsKeyService(repo, crypto);
        RSAKey second = reloaded.activeSigningKey("acme");

        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
        assertThat(store).hasSize(1);
    }
}
