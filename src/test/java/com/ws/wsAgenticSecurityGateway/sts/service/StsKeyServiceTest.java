package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.common.crypto.SecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
    private final SecretCryptoService crypto = mock(SecretCryptoService.class);
    private final GatewayAuditService audit = mock(GatewayAuditService.class);
    private final List<GatewayStsKeyEntity> store = new ArrayList<>();

    private StsKeyService keyService;

    @BeforeEach
    void setUp() {
        keyService = new StsKeyService(repo, crypto, audit);

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
    void listPublicKeys_returnsSafeMetadataOnly_neverPrivateKeyMaterial() {
        GatewayStsKeyEntity key = GatewayStsKeyEntity.builder()
                .kid("kid-1").status("ACTIVE").publicJwk("{\"kty\":\"RSA\"}")
                .privateKeyEnc("SECRET-ENCRYPTED-PRIVATE").wsTenantName("acme").build();
        when(repo.findByWsTenantNameOrderByCreatedAtDesc("acme")).thenReturn(List.of(key));

        List<Map<String, Object>> keys = keyService.listPublicKeys("acme");

        assertThat(keys).hasSize(1);
        Map<String, Object> k = keys.get(0);
        assertThat(k).containsKeys("kid", "status", "createdAt", "publicJwk");
        assertThat(k).doesNotContainKey("privateKeyEnc");
        assertThat(k.values()).doesNotContain("SECRET-ENCRYPTED-PRIVATE"); // private material never leaks
    }

    @Test
    void rotate_demotesCurrentActiveToRetiring_installsNewActive_andJwksKeepsBoth() throws Exception {
        RSAKey original = keyService.activeSigningKey("acme");
        String originalKid = original.getKeyID();
        assertThat(store).hasSize(1);

        when(repo.findByWsTenantNameAndStatus(eq("acme"), eq("ACTIVE")))
                .thenAnswer(inv -> store.stream()
                        .filter(k -> "acme".equals(k.getWsTenantName()) && "ACTIVE".equals(k.getStatus()))
                        .toList());
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        String newKid = keyService.rotate("acme", "manual");

        // new active key differs from the old one
        assertThat(newKid).isNotEqualTo(originalKid);
        // the previous key is now RETIRING, with a grace-window start stamped
        GatewayStsKeyEntity old = store.stream()
                .filter(k -> originalKid.equals(k.getKid())).findFirst().orElseThrow();
        assertThat(old.getStatus()).isEqualTo("RETIRING");
        assertThat(old.getRetiredAt()).isNotNull();
        // exactly one ACTIVE key now, and it is the new one
        assertThat(store.stream().filter(k -> "ACTIVE".equals(k.getStatus())).count()).isEqualTo(1);
        assertThat(keyService.activeSigningKey("acme").getKeyID()).isEqualTo(newKid);
        // JWKS keeps BOTH during the grace window, so tokens signed by the old key still verify
        JWKSet jwks = keyService.jwks("acme");
        assertThat(jwks.getKeyByKeyId(originalKid)).isNotNull();
        assertThat(jwks.getKeyByKeyId(newKid)).isNotNull();
        // the rotation is captured in the audit trail (manual trigger)
        verify(audit).auditStsKeyRotated(eq("acme"), eq(newKid), any(), eq("manual"));
    }

    @Test
    void retire_movesRetiringPastGraceToRetired_scrubsPrivateKey_leavesInGraceUntouched() {
        LocalDateTime now = LocalDateTime.now();
        GatewayStsKeyEntity stale = GatewayStsKeyEntity.builder()
                .kid("old").status("RETIRING").retiredAt(now.minusHours(2))
                .publicJwk("{}").privateKeyEnc("SECRET").wsTenantName("acme").build();
        GatewayStsKeyEntity recent = GatewayStsKeyEntity.builder()
                .kid("recent").status("RETIRING").retiredAt(now.minusMinutes(5))
                .publicJwk("{}").privateKeyEnc("SECRET").wsTenantName("acme").build();

        when(repo.findByStatusAndRetiredAtBefore(eq("RETIRING"), any())).thenAnswer(inv -> {
            LocalDateTime cutoff = inv.getArgument(1);
            return List.of(stale, recent).stream()
                    .filter(k -> k.getRetiredAt().isBefore(cutoff))
                    .toList();
        });
        when(repo.findByWsTenantNameAndStatusOrderByRetiredAtDesc(eq("acme"), eq("RETIRED")))
                .thenReturn(List.of(stale)); // one retired record → within the history cap, no trim

        int retired = keyService.retireExpiredKeys(Duration.ofHours(1));

        assertThat(retired).isEqualTo(1);
        // the stale key is now terminal RETIRED, with its private material scrubbed
        assertThat(stale.getStatus()).isEqualTo("RETIRED");
        assertThat(stale.getPrivateKeyEnc()).isEmpty();
        // the in-grace key is untouched — still RETIRING, private key intact for JWKS verification
        assertThat(recent.getStatus()).isEqualTo("RETIRING");
        assertThat(recent.getPrivateKeyEnc()).isEqualTo("SECRET");
        // the terminal retirement is captured per-kid in the audit trail
        verify(audit).auditStsKeyRetired("acme", "old");
    }

    @Test
    void retire_trimsRetiredHistoryBeyondCap() {
        LocalDateTime now = LocalDateTime.now();
        GatewayStsKeyEntity expiring = GatewayStsKeyEntity.builder()
                .kid("just-retired").status("RETIRING").retiredAt(now.minusHours(2))
                .publicJwk("{}").privateKeyEnc("SECRET").wsTenantName("acme").build();
        when(repo.findByStatusAndRetiredAtBefore(eq("RETIRING"), any())).thenReturn(List.of(expiring));

        // 7 RETIRED records already exist (newest-first); cap is 5 → the 2 oldest must be deleted
        List<GatewayStsKeyEntity> history = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            history.add(GatewayStsKeyEntity.builder()
                    .kid("r" + i).status("RETIRED").retiredAt(now.minusDays(i + 1))
                    .publicJwk("{}").privateKeyEnc("").wsTenantName("acme").build());
        }
        when(repo.findByWsTenantNameAndStatusOrderByRetiredAtDesc(eq("acme"), eq("RETIRED")))
                .thenReturn(history);
        List<GatewayStsKeyEntity> deleted = new ArrayList<>();
        doAnswer(inv -> { deleted.addAll(inv.getArgument(0)); return null; }).when(repo).deleteAll(any());

        keyService.retireExpiredKeys(Duration.ofHours(1));

        // keeps the 5 most recent (r0..r4), deletes the 2 oldest (r5, r6)
        assertThat(deleted).extracting(GatewayStsKeyEntity::getKid).containsExactly("r5", "r6");
    }

    @Test
    void reusesExistingActiveKey_doesNotRegenerate() {
        RSAKey first = keyService.activeSigningKey("acme");

        // a fresh service (empty in-memory cache) must reload the SAME key from the store, not mint a new one
        StsKeyService reloaded = new StsKeyService(repo, crypto, audit);
        RSAKey second = reloaded.activeSigningKey("acme");

        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
        assertThat(store).hasSize(1);
    }
}
