package com.ws.azureAdIntegration.service;
//
//import com.nimbusds.jose.*;
//import com.nimbusds.jose.crypto.RSASSAVerifier;
//import com.nimbusds.jose.jwk.JWK;
//import com.nimbusds.jose.jwk.RSAKey;
//import com.nimbusds.jwt.JWTClaimsSet;
//import com.nimbusds.jwt.SignedJWT;
//import com.ws.azureAdIntegration.entity.AzureUserCredential;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.http.client.methods.CloseableHttpResponse;
//import org.apache.http.client.methods.HttpGet;
//import org.apache.http.impl.client.HttpClients;
//import org.apache.http.impl.client.CloseableHttpClient;
//import org.apache.http.util.EntityUtils;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.security.interfaces.RSAPublicKey;
//import java.util.List;
//
//
//@Service
//@Slf4j
//public class AzureADJwtValidator {
//
//    private final AzureUserCredentialService azureUserCredentialService;
//
//    @Autowired
//    public AzureADJwtValidator(AzureUserCredentialService azureUserCredentialService) {
//        this.azureUserCredentialService = azureUserCredentialService;
//    }
//
//    public void validate(Integer wsTenantId, String jwtToken) {
//        AzureUserCredential azureUserCredential = azureUserCredentialService.findWSTeanantIdWithoutDecryptedSecret(wsTenantId);
////        validateToken(jwtToken, azureUserCredential.getTenantId(), azureUserCredential.getClientId());
//    }
//
////    private static void validateToken(String jwtToken, String tenantId, String clientId) {
////        try {
////            SignedJWT signedJWT = SignedJWT.parse(jwtToken);
////            String kid = signedJWT.getHeader().getKeyID();
////
////            RSAPublicKey publicKey = getPublicKeyFromJWKS(kid, tenantId);
////            if (publicKey == null) {
////                throw new Exception("Public key not found for the given kid.");
////            }
////
////            if (!verifySignature(signedJWT, publicKey)) {
////                throw new Exception("JWT signature is invalid.");
////            }
////
////            JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();
////            validateClaims(claimsSet, clientId, tenantId);
////
////            System.out.println("JWT is valid.");
////        } catch (Exception e) {
////            log.error("Erorr: {}", e.getMessage());
////        }
////    }
////
////    private static RSAPublicKey getPublicKeyFromJWKS(String kid, String tenantId) throws Exception {
////        String jwksUrl = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
////
////        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
////            HttpGet httpGet = new HttpGet(jwksUrl);
////            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
////                String responseBody = EntityUtils.toString(response.getEntity());
////
////                ObjectMapper objectMapper = new ObjectMapper();
////                JsonNode jsonResponse = objectMapper.readTree(responseBody);
////                List<JsonNode> keys = jsonResponse.get("keys").findValues("kid");
////
////                for (JsonNode key : keys) {
////                    if (key.asText().equals(kid)) {
////                        JWK jwk = JWK.parse(key.toString());
////                        if (jwk instanceof RSAKey) {
////                            RSAKey rsaKey = (RSAKey) jwk;
////                            return rsaKey.toRSAPublicKey();
////                        }
////                    }
////                }
////            }
////        }
////        return null;
////    }
////
////
////    /**
////     * Verify the JWT signature using the public key
////     */
////    private static boolean verifySignature(SignedJWT signedJWT, RSAPublicKey publicKey) throws Exception {
////        JWSVerifier verifier = new RSASSAVerifier(publicKey);
////        return signedJWT.verify(verifier);
////    }
////
////    /**
////     * Validate the claims in the JWT
////     */
////    private static void validateClaims(JWTClaimsSet claimsSet, String expectedClientId, String expectedTenantId) throws Exception {
////        if (!claimsSet.getAudience().contains(expectedClientId)) {
////            throw new IllegalArgumentException("Invalid audience.");
////        }
////
////        String expectedIssuer = "https://login.microsoftonline.com/" + expectedTenantId + "/v2.0";
////        if (!claimsSet.getIssuer().equals(expectedIssuer)) {
////            throw new IllegalArgumentException("Invalid issuer.");
////        }
////
////        if (claimsSet.getExpirationTime() != null && claimsSet.getExpirationTime().before(new java.util.Date())) {
////            throw new IllegalArgumentException("Token has expired.");
////        }
////
////        log.info("Success: {}", "JWT claims are valid");
////    }
//
//
//}


import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.json.*;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.RSAPublicKey;

@Service
@Slf4j
public class AzureADJwtValidator {
    private final AzureUserCredentialService azureUserCredentialService;

    @Autowired
    public AzureADJwtValidator(AzureUserCredentialService azureUserCredentialService) {
        this.azureUserCredentialService = azureUserCredentialService;
    }


    public void validate(Integer wsTenantId, String jwtToken) {
        AzureUserCredential azureUserCredential = azureUserCredentialService.findWSTeanantIdWithoutDecryptedSecret(wsTenantId);
        validateToken(jwtToken, azureUserCredential.getTenantId(), azureUserCredential.getClientId());
    }

    private static void validateToken(String jwtToken, String tenantId, String clientId) {
        try {
            // Decode the JWT token
            SignedJWT signedJWT = SignedJWT.parse(jwtToken);
            String kid = signedJWT.getHeader().getKeyID();

            log.info("kid: {}", kid);

            //  Fetch the public key from Azure AD's JWKS endpoint
            log.info("1");
            RSAPublicKey publicKey = getPublicKeyFromJWKS(kid, tenantId, clientId);
            if (publicKey == null) {
                throw new Exception("Public key not found for the given kid.");
            }
            log.info("2");

            // Verify the JWT signature using the public key
            if (!verifySignature(signedJWT, publicKey)) {
                throw new Exception("JWT signature is invalid.");
            }

            // Step 4: Validate JWT claims
            JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();
            validateClaims(claimsSet, clientId, tenantId);

            System.out.println("JWT is valid.");
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
        }
    }

    /**
     * Fetch the public key from Azure AD's JWKS endpoint using standard Java
     */
    private static RSAPublicKey getPublicKeyFromJWKS(String kid, String tenantId, String clientId) throws Exception {
//        String jwksUrl = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";
        String jwksUrl = "https://login.microsoftonline.com/" + tenantId + "/discovery/keys?appid=" + clientId;

        log.info(jwksUrl);

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
                if (key.getString("kid").equals(kid)) {

                    log.info("kid1: {}", kid);
                    log.info("kid2: {}", key.getString("kid"));

                    JsonArray x5cArray = key.getJsonArray("x5c");
                    if (x5cArray != null && !x5cArray.isEmpty()) {
                        String x5cBase64 = x5cArray.getString(0);
                        return convertToRSAPublicKey(x5cBase64);
                    }
                }
            }
        }

        return null;
    }


    /**
     * Convert a Base64-encoded X.509 certificate string to RSAPublicKey
     */
    private static RSAPublicKey convertToRSAPublicKey(String x5cBase64) throws Exception {
        byte[] decodedCert = java.util.Base64.getDecoder().decode(x5cBase64);
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(new java.io.ByteArrayInputStream(decodedCert));
        PublicKey publicKey = cert.getPublicKey();
        if (publicKey instanceof RSAPublicKey) {
            return (RSAPublicKey) publicKey;
        } else {
            throw new Exception("The public key is not an RSA public key.");
        }
    }

    /**
     * Verify the JWT signature using the public key
     */
    private static boolean verifySignature(SignedJWT signedJWT, RSAPublicKey publicKey) throws Exception {
        JWSVerifier verifier = new RSASSAVerifier(publicKey);
        return signedJWT.verify(verifier);
    }

    // Validate the claims in the JWT
    private static void validateClaims(JWTClaimsSet claimsSet, String clientId, String tenantId) {
        if (!claimsSet.getAudience().contains(clientId)) {
            throw new IllegalArgumentException("Invalid audience.");
        }

        String expectedIssuer = "https://login.microsoftonline.com/" + tenantId + "/v2.0";
        log.info(expectedIssuer);

        if (!claimsSet.getIssuer().equals(expectedIssuer)) {
            throw new IllegalArgumentException("Invalid issuer.");
        }

        if (claimsSet.getExpirationTime() != null && claimsSet.getExpirationTime().before(new java.util.Date())) {
            throw new IllegalArgumentException("Token has expired.");
        }

        log.info("Success: {}", "JWT claims are valid");
    }
}
