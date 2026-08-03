package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
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
    private static final int KEY_SIZE = 2048;

    private final GatewayStsKeyRepository repository;
    private final SecretCryptoService crypto;

    /** tenant -> active signing key (with private material), cached to avoid per-mint decrypt. */
    private final ConcurrentHashMap<String, RSAKey> signingCache = new ConcurrentHashMap<>();

    /**
     * A RETIRING key is purged once retired longer ago than this. MUST exceed the max OBO token TTL so no
     * still-valid token loses its verifier during the grace window. Default 1h ≫ the minutes-scale OBO TTL.
     */
    @Value("${ws.sts.key.grace-window:PT1H}")
    private Duration graceWindow;

    public StsKeyService(GatewayStsKeyRepository repository, SecretCryptoService crypto) {
        this.repository = repository;
        this.crypto = crypto;
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
     * the encrypted private material. Backs the admin "STS status" panel (newest key first).
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
    public String rotate(String tenant) {
        List<GatewayStsKeyEntity> current = repository.findByWsTenantNameAndStatus(tenant, ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        for (GatewayStsKeyEntity key : current) {
            key.setStatus(RETIRING);
            key.setRetiredAt(now);
        }
        if (!current.isEmpty()) {
            repository.saveAll(current);
        }
        signingCache.remove(tenant);
        RSAKey fresh = generateAndPersist(tenant);
        signingCache.put(tenant, fresh);
        log.info("Rotated STS signing key for tenant '{}': {} key(s) retired, new active kid={}",
                tenant, current.size(), fresh.getKeyID());
        return fresh.getKeyID();
    }

    /**
     * Remove RETIRING keys retired longer ago than {@code grace} (across all tenants). The grace window must
     * exceed the max OBO token TTL so no still-valid token loses its verifier. Returns the number purged.
     */
    @Transactional
    public int purgeExpiredRetiringKeys(Duration grace) {
        LocalDateTime cutoff = LocalDateTime.now().minus(grace);
        List<GatewayStsKeyEntity> expired = repository.findByStatusAndRetiredAtBefore(RETIRING, cutoff);
        if (!expired.isEmpty()) {
            repository.deleteAll(expired);
            log.info("Purged {} expired RETIRING STS key(s) (retired before {})", expired.size(), cutoff);
        }
        return expired.size();
    }

    /** Periodic sweep that purges RETIRING keys past the configured grace window. */
    @Scheduled(fixedDelayString = "${ws.sts.key.purge-interval-ms:3600000}")
    public void scheduledPurge() {
        try {
            purgeExpiredRetiringKeys(graceWindow);
        } catch (Exception e) {
            log.warn("STS key purge sweep failed: {}", e.getMessage());
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
