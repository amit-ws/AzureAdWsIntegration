package com.ws.certificateJIT.k8;

import java.util.Base64;

public class EncodingUtil {

    /**
     * Encode string to Base64
     */
    public static String encodeBase64(String input) {
        return Base64.getEncoder().encodeToString(input.getBytes());
    }

    /**
     * Decode Base64 string
     */
    public static String decodeBase64(String input) {
        return new String(Base64.getDecoder().decode(input));
    }

    /**
     * Remove newlines for CSR embedding in YAML
     */
    public static String compactBase64(String base64Encoded) {
        return base64Encoded.replaceAll("\\n", "").replaceAll("\\r", "");
    }
}
