package com.ws.azureAdIntegration.service;

import com.nimbusds.jwt.SignedJWT;
import jakarta.json.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADJwtValidationService {
    final Map<String, PublicKey> rsaPublicKeyMap = new ConcurrentHashMap<>();

    public void createAzureADPublicKeyMap(String tenantId, String clientId) {
        String jwksUrl = String.format("https://login.microsoftonline.com/%s/discovery/keys?appid=%s", tenantId, clientId);
        try {
            URL url = new URL(jwksUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            try (Reader reader = new InputStreamReader(connection.getInputStream())) {
                JsonReader jsonReader = Json.createReader(reader);
                JsonObject jsonResponse = jsonReader.readObject();

                JsonArray keys = jsonResponse.getJsonArray("keys");
                for (JsonObject key : keys.getValuesAs(JsonObject.class)) {
                    if ("RSA".equals(key.getString("kty"))) {
                        String modulus = key.getString("n");
                        String exponent = key.getString("e");
                        rsaPublicKeyMap.put(key.getString("kid"), getRSAPublicKey(modulus, exponent));
                    }
                }
            }
        } catch (Exception exp) {
            log.error("Error: {}", exp.getMessage());
            throw new RuntimeException(exp.getMessage());
        }
    }


    public boolean isAzureADTokenValid(String token) {
        try {
            return validateAzureADToken(token);
        } catch (Exception e) {
            log.error("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean validateAzureADToken(String jwtToken) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(jwtToken);
            String kid = signedJWT.getHeader().getKeyID();

            return Optional.ofNullable(kid)
                    .map(k -> rsaPublicKeyMap.containsKey(k))
                    .orElse(false);

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }


    private static PublicKey getRSAPublicKey(String modulusBase64, String exponentBase64) throws Exception {
        byte[] modulusBytes = Base64.getUrlDecoder().decode(modulusBase64);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(exponentBase64);

        java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
        java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

//    // Method to serialize the map with RSAPublicKey objects to JSON
//    public String serializePublicKeyMap(Map<String, RSAPublicKey> publicKeyMap) throws Exception {
//        ObjectMapper objectMapper = getObjectMapper();
//        return objectMapper.writeValueAsString(publicKeyMap);
//    }


    // Custom Serializer for RSAPublicKey
//    public static class RSAPublicKeySerializer extends JsonSerializer<RSAPublicKey> {
//        @Override
//        public void serialize(RSAPublicKey value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
//            // Extract modulus and exponent as BigInteger
//            String modulusBase64 = Base64.getUrlEncoder().encodeToString(value.getModulus().toByteArray());
//            String exponentBase64 = Base64.getUrlEncoder().encodeToString(value.getPublicExponent().toByteArray());
//
//            // Write as JSON object
//            gen.writeStartObject();
//            gen.writeStringField("n", modulusBase64); // Modulus in base64
//            gen.writeStringField("e", exponentBase64); // Exponent in base64
//            gen.writeEndObject();
//        }
//    }
//
//    // Method to get ObjectMapper with the custom serializer for RSAPublicKey
//    private static ObjectMapper getObjectMapper() {
//        SimpleModule module = new SimpleModule();
//        module.addSerializer(RSAPublicKey.class, new RSAPublicKeySerializer());
//
//        ObjectMapper objectMapper = new ObjectMapper();
//        objectMapper.registerModule(module);
//        return objectMapper;
//    }
}
