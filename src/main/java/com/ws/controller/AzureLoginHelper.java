//package com.ws.controller;
//import com.microsoft.aad.msal4j.ClientCredentialFactory;
//import com.microsoft.aad.msal4j.ClientCredentialParameters;
//import com.microsoft.aad.msal4j.ConfidentialClientApplication;
//import com.microsoft.aad.msal4j.IAuthenticationResult;
//import com.microsoft.graph.models.*;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.io.BufferedReader;
//import java.io.InputStreamReader;
//import java.net.HttpURLConnection;
//import java.net.MalformedURLException;
//import java.net.URL;
//import java.net.URLEncoder;
//import java.nio.charset.StandardCharsets;
//import java.util.*;
//import java.util.Calendar;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.ExecutionException;
//
//public class AzureLoginHelper {
//
//    private static final String clientId = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";
//    private static final String clientSecret = "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0";
//    private static final String tenantId = "00b1d06b-e316-45af-a6d2-2734f62a5acd";
//    private static final String scope = "https://graph.microsoft.com/.default";
//
//    public Map<String, String> createUserWithToken(String userName, Long passwordExpirationMinutes) {
//        String password = generateSecurePassword();
//        logger.warn("User: " + userName);
//        logger.warn("Password: " + password);
//
//        // Create or update user (assuming user creation logic is implemented)
//        User user = createOrUpdateUser(userName, password);
//
//        // Generate Access Token (session token)
//        String accessToken = getAccessToken();
//
//        // Prepare return map with details
//        Map<String, String> credentials = new HashMap<>();
//
//        // Generate expiration time (similar to AWS expiration date)
//        Calendar calendar = Calendar.getInstance();
//        calendar.setTime(new Date());
//        calendar.add(Calendar.MINUTE, Math.toIntExact(passwordExpirationMinutes));
//        Date expirationDate = calendar.getTime();
//
//        // Set credentials map
//        credentials.put("username", userName);
//        credentials.put("password", password);
//        credentials.put("accessToken", accessToken);
//        credentials.put("expiration", expirationDate.toString());
//
//        // Generate sign-in URL (similar to federated sign-in URL in AWS)
//        String federatedSignInUrl = generateFederatedSignInUrl(accessToken);
//        credentials.put("signinToken", getSigninToken(federatedSignInUrl));
//
//        return credentials;
//    }
//
//    private String generateSecurePassword() {
//        // Implement password generation logic
//        return "securePassword";
//    }
//
//    private User createOrUpdateUser(String userName, String password) {
//        // Logic to create or update user in Azure AD
//        User user = new User();
//        user.displayName = userName;
//        PasswordProfile passwordProfile = new PasswordProfile();
//        passwordProfile.password = password;
//        passwordProfile.forceChangePasswordNextSignIn = false;
//        user.passwordProfile = passwordProfile;
//
//        // Call Graphclient to persist the changes
////        graphClient.users()
////                .buildRequest()
////                .post(user);
//        return user;
//    }
//
//    private String getAccessToken() {
//        ConfidentialClientApplication app = null;
//        try {
//            app = ConfidentialClientApplication.builder(
//                            clientId,
//                            ClientCredentialFactory.createFromSecret(clientSecret))
//                    .authority("https://login.microsoftonline.com/" + tenantId)
//                    .build();
//        } catch (MalformedURLException e) {
//            throw new RuntimeException(e);
//        }
//
//        ClientCredentialParameters parameters = ClientCredentialParameters.builder(
//                        Collections.singleton("https://management.azure.com/.default"))
//                .build();
//
//        CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
//        IAuthenticationResult result = null;
//        try {
//            result = future.get();
//        } catch (InterruptedException | ExecutionException e) {
//            throw new RuntimeException(e);
//        }
//        String token = result.idToken();
//        return token;
//    }
//
//    private String generateFederatedSignInUrl(String accessToken) {
//        // Generate federated login URL (similarly to AWS's federated URL)
//        return String.format(
//                "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize?client_id=%s&response_type=token&scope=openid&access_token=%s",
//                tenantId, clientId, accessToken);
//    }
//
//    private String getSigninToken(String federatedSignInUrl) {
//        try {
//            // Create the URL object
//            URL url = new URL(federatedSignInUrl);
//
//            // Open a connection to the URL and set the method to GET
//            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
//            connection.setRequestMethod("GET");
//            connection.setRequestProperty("Accept", "application/json");
//
//            // Get the response code
//            int responseCode = connection.getResponseCode();
//
//            // If the response is OK (HTTP 200)
//            if (responseCode == HttpURLConnection.HTTP_OK) {
//                // Read the response from the input stream
//                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
//                String inputLine;
//                StringBuffer response = new StringBuffer();
//
//                while ((inputLine = in.readLine()) != null) {
//                    response.append(inputLine);
//                }
//                in.close();
//
//                // Convert the response to a string
//                String responseBody = response.toString();
//
//                // In Azure, the response will typically be a JSON object with an access token or similar info
//                // For the sake of this example, let's assume we extract a "signInToken" from the response body
//                // You can adjust this depending on the actual response format
//
//                int startIndex = responseBody.indexOf("\"signInToken\":\"") + "\"signInToken\":\"".length();
//                int endIndex = responseBody.indexOf("\"", startIndex);
//
//                // If the sign-in token is found in the response body, return it
//                if (startIndex > 0 && endIndex > startIndex) {
//                    return responseBody.substring(startIndex, endIndex);
//                } else {
//                    logger.error("SigninToken not found in the response.");
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
//
//    // Mock logger for demonstration
//    private static final Logger logger = LoggerFactory.getLogger(AzureLoginHelper.class);
//
//
//
//
//}
