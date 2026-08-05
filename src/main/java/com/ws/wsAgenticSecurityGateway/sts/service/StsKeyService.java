package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.common.crypto.SecretCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the per-tenant RSA signing keys the STS uses to mint OBO tokens.
 *
 * <p>The ACTIVE private key signs; the public JWK is served at the JWKS endpoint so downstream /
 * JWKS-trusting parties can verify gateway-minted tokens. Private key material is stored
 * AES/GCM-encrypted ({@link SecretCryptoService}). Keys are generated lazily on first use
 * for a tenant. (Decision: per-tenant keys — a single global key would risk cross-tenant leakage.)
 */
@Service
@Slf4j
public class StsKeyService {

    static final String ACTIVE = "ACTIVE";
    static final String RETIRING = "RETIRING";
    static final String RETIRED = "RETIRED";
    private static final int KEY_SIZE = 2048;

    /** How many RETIRED keys to retain per tenant as rotation history; older records are deleted. */
    static final int RETIRED_HISTORY_KEEP = 5;

    private final GatewayStsKeyRepository repository;
    private final SecretCryptoService crypto;
    private final GatewayAuditService auditService;

    /** tenant -> active signing key (with private material), cached to avoid per-mint decrypt. */
    private final ConcurrentHashMap<String, RSAKey> signingCache = new ConcurrentHashMap<>();

    /**
     * A RETIRING key becomes terminal RETIRED once retired longer ago than this. MUST exceed the max OBO token
     * TTL so no still-valid token loses its verifier during the grace window. Default 1h ≫ the minutes-scale OBO TTL.
     */
    @Value("${ws.sts.key.grace-window:PT1H}")
    private Duration graceWindow;

    public StsKeyService(GatewayStsKeyRepository repository, SecretCryptoService crypto,
                         GatewayAuditService auditService) {
        this.repository = repository;
        this.crypto = crypto;
        this.auditService = auditService;
    }

    /** The ACTIVE RSA signing key (with private) for a tenant; generated + persisted on first use. */
    public RSAKey activeSigningKey(String tenant) {
        return signingCache.computeIfAbsent(tenant, this::loadOrCreateActive);
    }

    /** Public-only JWK set (ACTIVE + RETIRING) for a tenant — served at the JWKS endpoint. */
    public JWKSet jwks(String tenant) {
        List<JWK> publicKeys = repository.findByWsTenantNameAndStatusIn(tenant, List.of(ACTIVE, RETIRING))
                .stream()
                .map(e -> parseJwk(e.getPublicJwk()))
                .filter(Objects::nonNull)
                .toList();
        return new JWKSet(publicKeys);
    }

    /**
     * Public metadata for a tenant's STS signing keys — {@code kid / status / createdAt / publicJwk}, never
     * the encrypted private material. Backs the admin "STS status" panel (newest key first). Returns every
     * live key (ACTIVE + RETIRING) plus the retained RETIRED history (bounded by {@link #RETIRED_HISTORY_KEEP});
     * the dashboard collapses this to the most recent few and offers a "load more".
     */
    public List<Map<String, Object>> listPublicKeys(String tenant) {
        return repository.findByWsTenantNameOrderByCreatedAtDesc(tenant).stream()
                .map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kid", k.getKid());
                    m.put("status", k.getStatus());
                    m.put("createdAt", k.getCreatedAt());
                    m.put("publicJwk", k.getPublicJwk());
                    return m;
                })
                .toList();
    }

    /**
     * Rotate a tenant's STS signing key: demote the current ACTIVE key(s) to RETIRING (kept in the JWKS
     * during the grace window so already-minted tokens still verify), mint a fresh ACTIVE key, and refresh
     * the signing cache so the next mint uses the new key. Returns the new key's {@code kid}.
     */
    @Transactional
    public String rotate(String tenant, String trigger) {
        List<GatewayStsKeyEntity> current = repository.findByWsTenantNameAndStatus(tenant, ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        List<String> retiredKids = new ArrayList<>();
        for (GatewayStsKeyEntity key : current) {
            key.setStatus(RETIRING);
            key.setRetiredAt(now);
            retiredKids.add(key.getKid());
        }
        if (!current.isEmpty()) {
            repository.saveAll(current);
        }
        signingCache.remove(tenant);
        RSAKey fresh = generateAndPersist(tenant);
        signingCache.put(tenant, fresh);
        log.info("Rotated STS signing key for tenant '{}': {} key(s) retired, new active kid={} (trigger={})",
                tenant, current.size(), fresh.getKeyID(), trigger);
        auditService.auditStsKeyRotated(tenant, fresh.getKeyID(), retiredKids, trigger);
        return fresh.getKeyID();
    }

    /**
     * Move RETIRING keys retired longer ago than {@code grace} to the terminal RETIRED state (across all
     * tenants): they drop out of the JWKS (RETIRED is not served), their private key material is scrubbed —
     * a RETIRED key is never used to sign — and the record is kept as rotation history (trimmed per tenant to
     * {@link #RETIRED_HISTORY_KEEP}). The grace window must exceed the max OBO token TTL so no still-valid token
     * loses its verifier. Returns the number newly retired.
     */
    @Transactional
    public int retireExpiredKeys(Duration grace) {
        LocalDateTime cutoff = LocalDateTime.now().minus(grace);
        List<GatewayStsKeyEntity> expiring = repository.findByStatusAndRetiredAtBefore(RETIRING, cutoff);
        if (expiring.isEmpty()) {
            return 0;
        }
        for (GatewayStsKeyEntity key : expiring) {
            key.setStatus(RETIRED);
            key.setPrivateKeyEnc(""); // scrub: the private half is no longer retained once out of the grace window
        }
        repository.saveAll(expiring);
        for (GatewayStsKeyEntity key : expiring) {
            auditService.auditStsKeyRetired(key.getWsTenantName(), key.getKid());
        }
        log.info("Retired {} STS key(s) past grace (dropped from JWKS, private material scrubbed, retired before {})",
                expiring.size(), cutoff);
        expiring.stream()
                .map(GatewayStsKeyEntity::getWsTenantName)
                .distinct()
                .forEach(this::trimRetiredHistory);
        return expiring.size();
    }

    /** Keep only the most-recently-retired {@link #RETIRED_HISTORY_KEEP} RETIRED keys for a tenant; delete older. */
    private void trimRetiredHistory(String tenant) {
        List<GatewayStsKeyEntity> retired =
                repository.findByWsTenantNameAndStatusOrderByRetiredAtDesc(tenant, RETIRED);
        if (retired.size() > RETIRED_HISTORY_KEEP) {
            List<GatewayStsKeyEntity> excess = new ArrayList<>(retired.subList(RETIRED_HISTORY_KEEP, retired.size()));
            repository.deleteAll(excess);
            log.info("Trimmed {} old RETIRED STS key record(s) for tenant '{}' (history cap {})",
                    excess.size(), tenant, RETIRED_HISTORY_KEEP);
        }
    }

    /** Periodic sweep: retire RETIRING keys past the grace window and trim RETIRED history to the cap. */
    @Scheduled(fixedDelayString = "${ws.sts.key.purge-interval-ms:3600000}")
    public void scheduledRetireSweep() {
        try {
            retireExpiredKeys(graceWindow);
        } catch (Exception e) {
            log.warn("STS key retire sweep failed: {}", e.getMessage());
        }
    }

    /** The {@code createdAt} of a tenant's ACTIVE signing key, or empty if none has been minted yet. */
    public Optional<LocalDateTime> activeKeyCreatedAt(String tenant) {
        return repository.findFirstByWsTenantNameAndStatus(tenant, ACTIVE)
                .map(GatewayStsKeyEntity::getCreatedAt);
    }

    private RSAKey loadOrCreateActive(String tenant) {
        Optional<GatewayStsKeyEntity> existing = repository.findFirstByWsTenantNameAndStatus(tenant, ACTIVE);
        return existing.map(this::toSigningKey).orElseGet(() -> generateAndPersist(tenant));
    }

    private RSAKey toSigningKey(GatewayStsKeyEntity entity) {
        try {
            return RSAKey.parse(crypto.decryptIfEncrypted(entity.getPrivateKeyEnc()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load STS signing key for tenant " + entity.getWsTenantName(), e);
        }
    }

    private RSAKey generateAndPersist(String tenant) {
        try {
            String kid = UUID.randomUUID().toString();
            RSAKey key = new RSAKeyGenerator(KEY_SIZE).keyID(kid).generate();
            GatewayStsKeyEntity entity = GatewayStsKeyEntity.builder()
                    .wsTenantName(tenant)
                    .kid(kid)
                    .publicJwk(key.toPublicJWK().toJSONString())
                    .privateKeyEnc(crypto.encrypt(key.toJSONString()))
                    .status(ACTIVE)
                    .build();
            repository.save(entity);
            log.info("Generated new ACTIVE STS signing key for tenant '{}' (kid={})", tenant, kid);
            return key;
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate STS signing key for tenant " + tenant, e);
        }
    }

    private JWK parseJwk(String json) {
        try {
            return RSAKey.parse(json);
        } catch (Exception e) {
            log.warn("Skipping unparseable STS public JWK: {}", e.getMessage());
            return null;
        }
    }
}
