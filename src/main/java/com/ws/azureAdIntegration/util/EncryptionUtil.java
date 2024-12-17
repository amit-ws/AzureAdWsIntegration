package com.ws.azureAdIntegration.util;

import com.ws.azureAdIntegration.constants.Constant;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EncryptionUtil {
    static final String ALGORITHM = Constant.ENCRYPTION_STANDARD;
    static final String TRANSFORMATION = Constant.ENCRYPTION_STANDARD;

    private static String SECRET_KEY = Constant.ENCRYPTION_KEY;

    static SecretKey secretKey;

    static {
        try {
            if (SECRET_KEY == null || SECRET_KEY.isEmpty()) {
                throw new RuntimeException("ENCRYPTION_KEY environment variable is not set!");
            }
            secretKey = new SecretKeySpec(SECRET_KEY.getBytes(), ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Error initializing encryption key", e);
        }
    }

    public static String getEncryptedKey(String input, String type) {
        return Optional.ofNullable(input)
                .map(secret -> {
                    try {
                        return encrypt(secret.trim());
                    } catch (Exception e) {
                        log.error("Encryption error: ", e.getMessage());
                        throw new RuntimeException(String.format("Failed to encrypt: %s", type));
                    }
                })
                .orElseThrow(() -> new RuntimeException(String.format("%s found as null", type)));
    }

    public static String getDecryptedKey(String input, String type) {
        return Optional.ofNullable(input)
                .map(secret -> {
                    try {
                        return decrypt(secret);
                    } catch (Exception e) {
                        log.error("Decryption error: ", e.getMessage());
                        throw new RuntimeException(String.format("Failed to decrypt: %s", type));
                    }
                })
                .orElse(String.format("%s found as null", type));
    }

    private static String encrypt(String input) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encryptedData = cipher.doFinal(input.getBytes());
        return Base64.getEncoder().encodeToString(encryptedData);
    }

    private static String decrypt(String encryptedData) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] decryptedData = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(decryptedData);
    }
}
