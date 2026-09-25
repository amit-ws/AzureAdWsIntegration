package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRevocationEntity;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsSessionRevocationEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRevocationRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsSessionRevocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OBO-token + session revocation registry: revoking a {@code jti} or a {@code sessionId}
 * makes the matching {@code isRevoked}/{@code isSessionRevoked} true, preloading warms the caches across
 * restarts, an absent expiry falls back to a default window, tenant listing is scoped, and the purge sweep
 * drops naturally-expired revocations from the caches.
 *
 * <p>The repositories are Mockito mocks backed by in-memory lists so the service's cache logic is exercised
 * end-to-end without a database.
 */
class StsRevocationServiceTest {

    private GatewayStsRevocationRepository repository;
    private GatewayStsSessionRevocationRepository sessionRepository;
    private final List<GatewayStsRevocationEntity> store = new ArrayList<>();
    private final List<GatewayStsSessionRevocationEntity> sessionStore = new ArrayList<>();

    private StsRevocationService service;

    @BeforeEach
    void setUp() {
        store.clear();
        sessionStore.clear();
        repository = mock(GatewayStsRevocationRepository.class);
        sessionRepository = mock(GatewayStsSessionRevocationRepository.class);

        // ── jti repo backed by `store` ──
        when(repository.save(any())).thenAnswer(inv -> {
            GatewayStsRevocationEntity e = inv.getArgument(0);
            store.removeIf(x -> e.getJti().equals(x.getJti()));
            store.add(e);
            return e;
        });
        when(repository.findByJti(any())).thenAnswer(inv ->
                store.stream().filter(x -> x.getJti().equals(inv.getArgument(0))).findFirst());
        when(repository.findByExpiresAtAfter(any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(0);
            return store.stream().filter(x -> x.getExpiresAt().isAfter(now)).toList();
        });
        when(repository.findByExpiresAtBefore(any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(0);
            return store.stream().filter(x -> x.getExpiresAt().isBefore(now)).toList();
        });
        when(repository.findByWsTenantNameAndExpiresAtAfter(any(), any())).thenAnswer(inv -> {
            String tenant = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            return store.stream()
                    .filter(x -> tenant.equals(x.getWsTenantName()) && x.getExpiresAt().isAfter(now))
                    .toList();
        });

        // ── session repo backed by `sessionStore` ──
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            GatewayStsSessionRevocationEntity e = inv.getArgument(0);
            sessionStore.removeIf(x -> e.getSessionId().equals(x.getSessionId()));
            sessionStore.add(e);
            return e;
        });
        when(sessionRepository.findBySessionId(any())).thenAnswer(inv ->
                sessionStore.stream().filter(x -> x.getSessionId().equals(inv.getArgument(0))).findFirst());
        when(sessionRepository.findByExpiresAtAfter(any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(0);
            return sessionStore.stream().filter(x -> x.getExpiresAt().isAfter(now)).toList();
        });
        when(sessionRepository.findByExpiresAtBefore(any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(0);
            return sessionStore.stream().filter(x -> x.getExpiresAt().isBefore(now)).toList();
        });
        when(sessionRepository.findByWsTenantNameAndExpiresAtAfter(any(), any())).thenAnswer(inv -> {
            String tenant = inv.getArgument(0);
            LocalDateTime now = inv.getArgument(1);
            return sessionStore.stream()
                    .filter(x -> tenant.equals(x.getWsTenantName()) && x.getExpiresAt().isAfter(now))
                    .toList();
        });

        service = new StsRevocationService(repository, sessionRepository);
        service.loadActive();
    }

    // ── Per-token (jti) ──────────────────────────────────────────────

    @Test
    void revoke_marksJtiRevoked() {
        assertThat(service.isRevoked("jti-1")).isFalse();

        service.revoke("acme", "jti-1", LocalDateTime.now().plusMinutes(2), "compromised");

        assertThat(service.isRevoked("jti-1")).isTrue();
    }

    @Test
    void isRevoked_falseForNullOrUnknownJti() {
        assertThat(service.isRevoked(null)).isFalse();
        assertThat(service.isRevoked("never-seen")).isFalse();
    }

    @Test
    void revoke_withoutExpiry_usesDefaultWindowInFuture() {
        GatewayStsRevocationEntity saved = service.revoke("acme", "jti-2", null, null);

        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(service.isRevoked("jti-2")).isTrue();
    }

    @Test
    void loadActive_warmsCacheFromStillActiveRows() {
        store.add(GatewayStsRevocationEntity.builder()
                .jti("preexisting").wsTenantName("acme")
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build());

        StsRevocationService fresh = new StsRevocationService(repository, sessionRepository);
        fresh.loadActive();

        assertThat(fresh.isRevoked("preexisting")).isTrue();
    }

    @Test
    void listActive_returnsOnlyTenantsUnexpiredRevocations() {
        service.revoke("acme", "acme-jti", LocalDateTime.now().plusMinutes(5), null);
        service.revoke("other", "other-jti", LocalDateTime.now().plusMinutes(5), null);

        List<GatewayStsRevocationEntity> active = service.listActive("acme");

        assertThat(active).extracting(GatewayStsRevocationEntity::getJti).containsExactly("acme-jti");
    }

    @Test
    void purgeExpired_dropsNaturallyExpiredFromCache() {
        // an already-expired revocation still lands in the cache when revoked...
        service.revoke("acme", "stale", LocalDateTime.now().minusMinutes(1), null);
        assertThat(service.isRevoked("stale")).isTrue();

        service.purgeExpired();

        // ...and the sweep evicts exactly the expired key
        assertThat(service.isRevoked("stale")).isFalse();
    }

    @Test
    void purgeExpired_keepsStillActiveRevocations_evictingOnlyExpired() {
        service.revoke("acme", "fresh", LocalDateTime.now().plusMinutes(10), null);
        service.revoke("acme", "old", LocalDateTime.now().minusMinutes(1), null);

        service.purgeExpired();

        // Only the expired key is dropped; a still-active revocation must never be evicted by the sweep
        // (regression guard for the retainAll-against-snapshot race).
        assertThat(service.isRevoked("fresh")).isTrue();
        assertThat(service.isRevoked("old")).isFalse();
    }

    // ── Per-session ──────────────────────────────────────────────────

    @Test
    void revokeSession_marksSessionRevoked() {
        assertThat(service.isSessionRevoked("sess-1")).isFalse();

        service.revokeSession("acme", "sess-1", null, "kill switch");

        assertThat(service.isSessionRevoked("sess-1")).isTrue();
    }

    @Test
    void isSessionRevoked_falseForNullOrUnknownSession() {
        assertThat(service.isSessionRevoked(null)).isFalse();
        assertThat(service.isSessionRevoked("never-seen")).isFalse();
    }

    @Test
    void revokeSession_withoutExpiry_usesMultiHourWindow() {
        GatewayStsSessionRevocationEntity saved = service.revokeSession("acme", "sess-2", null, null);

        // sessions outlive tokens — default window is hours, comfortably in the future
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(1));
        assertThat(service.isSessionRevoked("sess-2")).isTrue();
    }

    @Test
    void loadActive_warmsSessionCacheFromStillActiveRows() {
        sessionStore.add(GatewayStsSessionRevocationEntity.builder()
                .sessionId("pre-sess").wsTenantName("acme")
                .expiresAt(LocalDateTime.now().plusHours(1)).build());

        StsRevocationService fresh = new StsRevocationService(repository, sessionRepository);
        fresh.loadActive();

        assertThat(fresh.isSessionRevoked("pre-sess")).isTrue();
    }

    @Test
    void listActiveSessions_isTenantScoped() {
        service.revokeSession("acme", "acme-sess", LocalDateTime.now().plusHours(1), null);
        service.revokeSession("other", "other-sess", LocalDateTime.now().plusHours(1), null);

        List<GatewayStsSessionRevocationEntity> active = service.listActiveSessions("acme");

        assertThat(active).extracting(GatewayStsSessionRevocationEntity::getSessionId).containsExactly("acme-sess");
    }

    @Test
    void purgeExpired_dropsExpiredSessionFromCache() {
        service.revokeSession("acme", "stale-sess", LocalDateTime.now().minusMinutes(1), null);
        assertThat(service.isSessionRevoked("stale-sess")).isTrue();

        service.purgeExpired();

        assertThat(service.isSessionRevoked("stale-sess")).isFalse();
    }
}
