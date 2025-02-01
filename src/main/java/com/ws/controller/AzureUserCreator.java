//package com.ws.controller;
//
//import com.azure.core.credential.TokenRequestContext;
//import com.microsoft.graph.models.*;
//import com.microsoft.graph.requests.*;
//import com.azure.identity.*;
//import com.azure.security.keyvault.secrets.*;
//import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
//import okhttp3.*;
//import okhttp3.Request;
//
//import java.util.*;
//import java.time.*;
//import java.util.concurrent.TimeUnit;
//
//public class AzureUserCreator {
//
//    private final String tenantId;
//    private final String clientId;
//    private final String clientSecret;
//    private final String subscriptionId;
//
//    public AzureUserCreator(String tenantId, String clientId, String clientSecret, String subscriptionId) {
//        this.tenantId = tenantId;
//        this.clientId = clientId;
//        this.clientSecret = clientSecret;
//        this.subscriptionId = subscriptionId;
//    }
//
//    public Map<String, String> createUserWithPasswordAndToken(String userName, String roleId, String scope) {
//        Map<String, String> credentials = new HashMap<>();
//
//        // 1. Create user in Azure AD
//        User user = createAzureADUser(userName);
//        String password = generateSecurePassword();
//        setUserPassword(user.id, password);
//
//        // 2. Assign RBAC role
//        assignRBACRole(user.id, roleId, scope);
//
//        // 3. Get token using ROPC (Not recommended, but shown for example)
//        String accessToken = getAccessTokenWithROPC(user.userPrincipalName, password);
//
//        credentials.put("username", user.userPrincipalName);
//        credentials.put("password", password);
//        credentials.put("accessToken", accessToken);
//        credentials.put("expiresIn", "3600"); // Default token expiration
//
//        return credentials;
//    }
//
//    private User createAzureADUser(String userName) {
//        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
//                .tenantId(tenantId)
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .build();
//
//        TokenCredentialAuthProvider authProvider = new TokenCredentialAuthProvider(
//                credential);
//
//        GraphServiceClient<Request> graphClient = GraphServiceClient.builder()
//                .authenticationProvider(authProvider)
//                .buildClient();
//
//        User user = new User();
//        user.accountEnabled = true;
//        user.displayName = userName;
//        user.mailNickname = userName;
//        user.userPrincipalName = userName + "@" + tenantId + ".onmicrosoft.com";
//        user.passwordProfile = new PasswordProfile();
//        user.passwordProfile.forceChangePasswordNextSignIn = false;
//        user.passwordProfile.password = generateSecurePassword();
//
//        return graphClient.users()
//                .buildRequest()
//                .post(user);
//    }
//
//    private void setUserPassword(String userId, String password) {
//        // Similar approach to update password if needed
//    }
//
//    private void assignRBACRole(String userId, String roleId, String scope) {
//        // Use Azure Management REST API or SDK to assign role
//        // Example: assign 'Contributor' role at subscription scope
//    }
//
//    private String getAccessTokenWithROPC(String username, String password) {
////        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
////                .tenantId(tenantId)
////                .clientId(clientId)
////                .clientSecret(clientSecret)
////                .build();
//
//        TokenRequestContext requestContext = new TokenRequestContext()
//                .addScopes("https://management.azure.com/.default");
//
//        // Using ROPC (Not recommended - shown for example only)
//        UsernamePasswordCredential ropcCredential = new UsernamePasswordCredentialBuilder()
//                .tenantId(tenantId)
//                .clientId(clientId)
//                .username(username)
//                .password(password)
//                .build();
//
//        return Objects.requireNonNull(ropcCredential.getToken(requestContext).block()).getToken();
//    }
//
//    private String generateSecurePassword() {
//        // Implement secure password generation
//        return "GeneratedSecurePassword123!";
//    }
//}