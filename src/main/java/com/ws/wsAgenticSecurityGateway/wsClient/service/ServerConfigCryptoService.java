package com.ws.wsAgenticSecurityGateway.wsClient.service;

import com.ws.azureAdIntegration.constants.Constant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts/decrypts sensitive server-config values for DB at-rest protection.
 *
 * <p>Storage format: {@code ENCv1:<base64(iv + ciphertextAndTag)>}
 */
@Service
@Slf4j
public class ServerConfigCryptoService {

    private static final String ENC_PREFIX = "ENCv1:";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ServerConfigCryptoService(
            @Value("${ws.gateway.server-config-encryption.key:}") String keyMaterial) {
        String effectiveKey = keyMaterial;
        if (effectiveKey == null || effectiveKey.isBlank()) {
            effectiveKey = Constant.ENCRYPTION_KEY;
            log.warn("⚠️ ws.gateway.server-config-encryption.key not set. Falling back to Constant.ENCRYPTION_KEY.");
        }
        if (effectiveKey == null || effectiveKey.isBlank()) {
            throw new IllegalStateException(
                    "Server config encryption key is missing. Set ws.gateway.server-config-encryption.key " +
                    "or configure Constant.ENCRYPTION_KEY.");
        }

        this.secretKey = deriveKey(effectiveKey);
        log.info("🔐 Server config crypto service initialized");
    }

    public boolean isEncryptedValue(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }
        if (isEncryptedValue(plainText)) {
            return plainText;
        }
        ensureKeyAvailable();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer packed = ByteBuffer.allocate(iv.length + encrypted.length);
            packed.put(iv);
            packed.put(encrypted);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(packed.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt server config value", e);
        }
    }

    public String decryptIfEncrypted(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!isEncryptedValue(value)) {
            return value;
        }
        ensureKeyAvailable();

        try {
            String payload = value.substring(ENC_PREFIX.length());
            byte[] packed = Base64.getDecoder().decode(payload);
            if (packed.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Encrypted payload is invalid");
            }

            ByteBuffer buffer = ByteBuffer.wrap(packed);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherBytes = new byte[buffer.remaining()];
            buffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherBytes);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt server config value", e);
        }
    }

    private SecretKey deriveKey(String keyMaterial) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    private void ensureKeyAvailable() {
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Server config encryption key is missing. Set ws.gateway.server-config-encryption.key " +
                    "or configure Constant.ENCRYPTION_KEY.");
        }
    }
}
