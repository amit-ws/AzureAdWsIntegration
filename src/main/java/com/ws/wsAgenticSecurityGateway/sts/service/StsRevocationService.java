package com.ws.wsAgenticSecurityGateway.sts.service;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRevocationEntity;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsSessionRevocationEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsRevocationRepository;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsSessionRevocationRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Revocation registry for gateway-minted OBO delegations — invalidates access before natural TTL expiry, at
 * two granularities:
 * <ul>
 *   <li><b>Per-token</b> ({@link #isRevoked(String)}, keyed by minted OBO {@code jti}) — surgical: kill one
 *       token. Enforced honor-time when that token is presented on a later hop (A2A / multi-hop).</li>
 *   <li><b>Per-session</b> ({@link #isSessionRevoked(String)}, keyed by agent {@code sessionId}) — kill a
 *       whole live chain: the gateway refuses to mint any further OBO token for that session (mint-time,
 *       fail-closed) and rejects its inbound requests at the door (honor-time). This is the switch behind the
 *       in-flight page's "Revoke session".</li>
 * </ul>
 *
 * <p>Both are O(1) in-memory checks (the hot path) backed by the DB for durability across restarts.
 *
 * <p>Per-instance cache (a shared/pub-sub store so a revoke on one instance is enforced on all instances
 * within ms is Stage 2.5's "distributed enforcement state" item). Today a revoke is enforced immediately on
 * the instance that handled it and on other instances after their next restart/purge reload.
 */
@Service
@Slf4j
public class StsRevocationService {

    /** Default token-revocation window when the token's real expiry is unknown — past the minutes-scale OBO TTL. */
    private static final int DEFAULT_WINDOW_MINUTES = 15;

    /** Default session-revocation window — sessions outlive tokens, so this is hours, and it self-purges. */
    private static final int DEFAULT_SESSION_WINDOW_HOURS = 6;

    private final GatewayStsRevocationRepository repository;
    private final GatewayStsSessionRevocationRepository sessionRepository;

    private final Set<String> revokedJtis = ConcurrentHashMap.newKeySet();
    private final Set<String> revokedSessions = ConcurrentHashMap.newKeySet();

    public StsRevocationService(GatewayStsRevocationRepository repository,
                                GatewayStsSessionRevocationRepository sessionRepository) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
    }

    /** Warm both in-memory sets from still-active revocations so restarts don't lose enforcement. */
    @PostConstruct
    public void loadActive() {
        try {
            LocalDateTime now = LocalDateTime.now();
            repository.findByExpiresAtAfter(now).forEach(r -> revokedJtis.add(r.getJti()));
            sessionRepository.findByExpiresAtAfter(now).forEach(r -> revokedSessions.add(r.getSessionId()));
            log.info("Loaded {} active OBO token revocation(s) and {} active session revocation(s) into the check cache",
                    revokedJtis.size(), revokedSessions.size());
        } catch (Exception e) {
            log.warn("Could not preload OBO revocations (will fill as revocations occur): {}", e.getMessage());
        }
    }

    // ── Per-token (jti) ──────────────────────────────────────────────

    /** True if the token with this {@code jti} has been revoked (and not yet purged). */
    public boolean isRevoked(String jti) {
        return jti != null && revokedJtis.contains(jti);
    }

    /**
     * Revoke a minted OBO token by {@code jti}. {@code expiresAt} is the token's natural expiry (from its OBO
     * receipt) so the revocation can be purged once the token would be rejected on TTL anyway; when unknown a
     * short default window is used. Idempotent per {@code jti}.
     */
    @Transactional
    public GatewayStsRevocationEntity revoke(String tenant, String jti, LocalDateTime expiresAt, String reason) {
        LocalDateTime expiry = expiresAt != null ? expiresAt
                : LocalDateTime.now().plusMinutes(DEFAULT_WINDOW_MINUTES);
        GatewayStsRevocationEntity entity = repository.findByJti(jti)
                .orElseGet(() -> GatewayStsRevocationEntity.builder().jti(jti).build());
        entity.setWsTenantName(tenant);
        entity.setExpiresAt(expiry);
        entity.setReason(reason);
        GatewayStsRevocationEntity saved = repository.save(entity);
        revokedJtis.add(jti);
        log.info("Revoked OBO token jti={} (tenant={}, until={}, reason={})", jti, tenant, expiry, reason);
        return saved;
    }

    /** Still-active token revocations for a tenant (for the admin list). */
    public List<GatewayStsRevocationEntity> listActive(String tenant) {
        return repository.findByWsTenantNameAndExpiresAtAfter(tenant, LocalDateTime.now());
    }

    // ── Per-session ──────────────────────────────────────────────────

    /** True if the whole agent {@code sessionId} has been revoked (and not yet purged). */
    public boolean isSessionRevoked(String sessionId) {
        return sessionId != null && revokedSessions.contains(sessionId);
    }

    /**
     * Revoke an entire agent session — kills the live chain: no further OBO tokens are minted for it and its
     * inbound requests are rejected. {@code expiresAt} defaults to a multi-hour window (sessions outlive
     * tokens) so the row self-purges. Idempotent per {@code sessionId}.
     */
    @Transactional
    public GatewayStsSessionRevocationEntity revokeSession(String tenant, String sessionId,
                                                           LocalDateTime expiresAt, String reason) {
        LocalDateTime expiry = expiresAt != null ? expiresAt
                : LocalDateTime.now().plusHours(DEFAULT_SESSION_WINDOW_HOURS);
        GatewayStsSessionRevocationEntity entity = sessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> GatewayStsSessionRevocationEntity.builder().sessionId(sessionId).build());
        entity.setWsTenantName(tenant);
        entity.setExpiresAt(expiry);
        entity.setReason(reason);
        GatewayStsSessionRevocationEntity saved = sessionRepository.save(entity);
        revokedSessions.add(sessionId);
        log.info("Revoked agent session={} (tenant={}, until={}, reason={})", sessionId, tenant, expiry, reason);
        return saved;
    }

    /** Still-active session revocations for a tenant (for the admin list). */
    public List<GatewayStsSessionRevocationEntity> listActiveSessions(String tenant) {
        return sessionRepository.findByWsTenantNameAndExpiresAtAfter(tenant, LocalDateTime.now());
    }

    // ── Maintenance ──────────────────────────────────────────────────

    /**
     * Hourly sweep: drop revocations whose tokens/sessions have naturally expired. Evicts ONLY the keys proven
     * expired (never {@code retainAll} against a snapshot of the still-active set — that would race out a
     * revocation committed on another thread between the SELECT and the rebuild, silently un-revoking it).
     */
    @Scheduled(fixedDelayString = "${ws.sts.revocation.purge-interval-ms:3600000}")
    @Transactional
    public void purgeExpired() {
        try {
            LocalDateTime now = LocalDateTime.now();

            Set<String> expiredJtis = repository.findByExpiresAtBefore(now).stream()
                    .map(GatewayStsRevocationEntity::getJti).collect(Collectors.toSet());
            repository.deleteByExpiresAtBefore(now);
            revokedJtis.removeAll(expiredJtis);

            Set<String> expiredSessions = sessionRepository.findByExpiresAtBefore(now).stream()
                    .map(GatewayStsSessionRevocationEntity::getSessionId).collect(Collectors.toSet());
            sessionRepository.deleteByExpiresAtBefore(now);
            revokedSessions.removeAll(expiredSessions);
        } catch (Exception e) {
            log.warn("OBO revocation purge sweep failed: {}", e.getMessage());
        }
    }
}
