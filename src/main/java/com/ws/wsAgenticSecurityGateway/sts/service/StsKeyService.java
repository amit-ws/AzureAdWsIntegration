package com.ws.wsAgenticSecurityGateway.sts.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import com.ws.wsAgenticSecurityGateway.sts.repository.GatewayStsKeyRepository;
import com.ws.wsAgenticSecurityGateway.wsClient.service.ServerConfigCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the per-tenant RSA signing keys the STS uses to mint OBO tokens.
 *
 * <p>The ACTIVE private key signs; the public JWK is served at the JWKS endpoint so downstream /
 * JWKS-trusting parties can verify gateway-minted tokens. Private key material is stored
 * AES/GCM-encrypted ({@link ServerConfigCryptoService}). Keys are generated lazily on first use
 * for a tenant. (Decision: per-tenant keys — a single global key would risk cross-tenant leakage.)
 */
@Service
@Slf4j
public class StsKeyService {

    static final String ACTIVE = "ACTIVE";
    static final String RETIRING = "RETIRING";
    private static final int KEY_SIZE = 2048;

    private final GatewayStsKeyRepository repository;
    private final ServerConfigCryptoService crypto;

    /** tenant -> active signing key (with private material), cached to avoid per-mint decrypt. */
    private final ConcurrentHashMap<String, RSAKey> signingCache = new ConcurrentHashMap<>();

    public StsKeyService(GatewayStsKeyRepository repository, ServerConfigCryptoService crypto) {
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
