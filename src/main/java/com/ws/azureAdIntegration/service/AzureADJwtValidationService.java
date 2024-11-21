package com.ws.azureAdIntegration.service;

import com.nimbusds.jwt.SignedJWT;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.text.ParseException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADJwtValidationService {
    private final Map<String, RSAPublicKey> rsaPublicKeyMap = new ConcurrentHashMap<>();
    final String CLIENT_ID = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";
    final String TENANT_ID = "00b1d06b-e316-45af-a6d2-2734f62a5acd";


    public boolean validateJWTToken(String jwtToken) {
        log.info("Initial map size: {}", rsaPublicKeyMap.size());
        log.info("Creating map of the RSAPublicKeys...");
        createRSAPublicKeyMapAgainstKIDID();
        log.info("Map created. Total size: {}", rsaPublicKeyMap.size());

        SignedJWT signedJWT;
        try {
            signedJWT = SignedJWT.parse(jwtToken);
        } catch (ParseException e) {
            throw new RuntimeException(e.getMessage());
        }
        String kid = signedJWT.getHeader().getKeyID();

        try {
            if (rsaPublicKeyMap.containsKey(kid)) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }

    private void createRSAPublicKeyMapAgainstKIDID() {
        log.info("Into service...");
        Map<String, RSAPublicKey> publicKeyMap = new HashMap<>();
        String jwksUrl = "https://login.microsoftonline.com/" + TENANT_ID + "/discovery/keys?appid=" + CLIENT_ID;

        try {
            // Open connection to the JWKS URL
            URL url = new URL(jwksUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            try (Reader reader = new InputStreamReader(connection.getInputStream())) {
                JsonReader jsonReader = Json.createReader(reader);
                JsonObject jsonResponse = jsonReader.readObject();

                // Iterate through the keys in the response
                JsonArray keys = jsonResponse.getJsonArray("keys");
                for (JsonObject key : keys.getValuesAs(JsonObject.class)) {
                    if ("RSA".equals(key.getString("kty"))) {
                        String kidID = key.getString("kid");
                        String modulus = key.getString("n");
                        String exponent = key.getString("e");
                        RSAPublicKey rsaPublicKey = getRSAPublicKey(modulus, exponent);
                        rsaPublicKeyMap.put(kidID, rsaPublicKey);
                    }
                }
            }
        } catch (Exception exp) {
            log.error("Error: {}", exp.getMessage());
            throw new RuntimeException(exp.getMessage());
        }
    }

    private static RSAPublicKey getRSAPublicKey(String modulusBase64, String exponentBase64) throws Exception {
        log.info("Into getRSAPublicKey...");
        byte[] modulusBytes = Base64.getUrlDecoder().decode(modulusBase64);
        byte[] exponentBytes = Base64.getUrlDecoder().decode(exponentBase64);

        java.math.BigInteger modulus = new java.math.BigInteger(1, modulusBytes);
        java.math.BigInteger exponent = new java.math.BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(spec);

        return publicKey;
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
