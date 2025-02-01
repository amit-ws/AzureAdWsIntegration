//package com.ws.controller;
//
//import com.azure.core.credential.TokenCredential;
//import com.azure.identity.ClientSecretCredentialBuilder;
//import com.azure.core.management.AzureEnvironment;
//import com.azure.identity.InteractiveBrowserCredential;
//import com.azure.identity.UsernamePasswordCredential;
//import com.azure.core.util.logging.ClientLogger;
//import com.azure.identity.ClientSecretCredential;
//import com.azure.core.management.profile.AzureProfile;
//import com.azure.core.http.HttpRequest;
//import com.azure.core.http.HttpResponse;
//import com.azure.core.http.HttpMethod;
//import com.azure.core.util.Configuration;
//
//import java.io.BufferedReader;
//import java.io.InputStreamReader;
//import java.net.HttpURLConnection;
//import java.net.URL;
//import java.util.Map;
//import java.util.HashMap;
//import java.util.Optional;
//
//public class AzureRoleAssumption {
//    private static final String clientId = "your-client-id";
//    private static final String clientSecret = "your-client-secret";
//    private static final String tenantId = "your-tenant-id";
//    private static final String scope = "https://graph.microsoft.com/.default";
//    private static final ClientLogger logger = new ClientLogger(AzureRoleAssumption.class);
//
//    public Map<String, String> createUserWithPasswordAndSessionToken(String tenantId, String clientId, String clientSecret, String userName, Long passwordExpirationMinutes, String roleDefinitionId, String externalId, String tenantName) {
//        String password = generateSecurePassword();  // Define your password generation logic here
//
//        logger.info("Username: {}", userName);
//        logger.info("Password: {}", password);
//
//        // Assuming the Azure identity is created here
//        TokenCredential tokenCredential = new ClientSecretCredentialBuilder()
//                .tenantId(tenantId)
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .build();
//
//        // Assume Role in Azure - Assign Role to the User
//        String roleAssignmentId = assignRoleToUser(tokenCredential, roleDefinitionId, userName);
//
//        // Generate Session Token
//        String accessToken = getAccessToken(tokenCredential, tenantId);
//
//        // Generate Federated SignIn URL
//        String federatedSignInUrl = generateFederatedSignInUrl(accessToken, passwordExpirationMinutes);
//
//        // Create Map to Return
//        Map<String, String> credentials = new HashMap<>();
//        credentials.put("username", userName);
//        credentials.put("password", password);
//        credentials.put("accessToken", accessToken);
//        credentials.put("roleAssignmentId", roleAssignmentId);
//        credentials.put("expiration", String.valueOf(passwordExpirationMinutes));
//        credentials.put("region", "AzureRegion");  // Define Azure Region as needed
//        credentials.put("signinToken", getSigninToken(federatedSignInUrl));  // Get SignIn Token
//
//        return credentials;
//    }
//
//    private String assignRoleToUser(TokenCredential tokenCredential, String roleDefinitionId, String userName) {
//        // Use Azure SDK to assign role to the user
//        logger.info("Assigning role to user: {}", userName);
//
//        // Example of assigning role using Azure RBAC (Role-based access control)
//        // Here we would use Azure's role assignment API to assign a role to a user
//        // Ensure you are calling the Azure API to assign role based on the roleDefinitionId and userName
//
//        // Return the role assignment ID
//        return "roleAssignmentId";
//    }
//
//    private String getAccessToken(TokenCredential tokenCredential, String tenantId) {
//        // Get access token from Azure AD
//        String accessToken = "";
//        try {
//            logger.info("Getting Access Token for tenant: {}", tenantId);
//            // Example method that requests an access token from Azure AD
////            String accessToken = tokenCredential.getToken(AzureProfile.AzureEnvironment().defaultScope()).block().getToken();
//            return accessToken;
//        } catch (Exception e) {
//            logger.error("Error fetching access token", e);
//            return null;
//        }
//    }
//
//    private String generateFederatedSignInUrl(String accessToken, long durationSeconds) {
//        // Create the federated sign-in URL using Azure's login URL
//        String sessionJson = String.format("{\"accessToken\":\"%s\"}", accessToken);
//        String encodedSessionData = java.net.URLEncoder.encode(sessionJson, java.nio.charset.StandardCharsets.UTF_8);
//
//        return String.format("https://login.microsoftonline.com/%s/oauth2/v2.0/authorize?response_type=code&client_id=%s&scope=%s&state=%s&redirect_uri=%s",
//                tenantId, clientId, "openid profile", "state", "redirectUri");
//    }
//
//    private String getSigninToken(String federatedSignInUrl) {
//        try {
//            URL url = new URL(federatedSignInUrl);
//            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
//            connection.setRequestMethod("GET");
//            connection.setRequestProperty("Accept", "application/json");
//
//            int responseCode = connection.getResponseCode();
//            if (responseCode == HttpURLConnection.HTTP_OK) {
//                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
//                String inputLine;
//                StringBuffer response = new StringBuffer();
//
//                while ((inputLine = in.readLine()) != null) {
//                    response.append(inputLine);
//                }
//                in.close();
//
//                String responseBody = response.toString();
//                int startIndex = responseBody.indexOf("\"signinToken\":\"") + "\"signinToken\":\"".length();
//                int endIndex = responseBody.indexOf("\"", startIndex);
//                if (startIndex > 0 && endIndex > startIndex) {
//                    return responseBody.substring(startIndex, endIndex);
//                }
//            } else {
//                logger.error("Error getting SigninToken, Response Code: " + responseCode);
//            }
//        } catch (Exception e) {
//            logger.error("Error getting SigninToken", e);
//        }
//        return null;
//    }
//
//    private String generateSecurePassword() {
//        // Password generation logic
//        return "SecurePassword123!";
//    }
//}
