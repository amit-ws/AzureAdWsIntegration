package com.ws.service;

import com.microsoft.graph.models.*;
import com.microsoft.graph.requests.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AzureADService {

    //    @Value("${spring.cloud.azure.active-directory.client-id}")
    private String clientId = "9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa";

    //    @Value("${spring.cloud.azure.active-directory.client-secret}")
    private String clientSecret = "sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0";

    //    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    private String tenantId = "00b1d06b-e316-45af-a6d2-2734f62a5acd";

    private GraphServiceClient<Request> graphClient;



//    public AzureADService() {
//        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .tenantId(tenantId)
//                .build();
//
//        List<String> scopes = new LinkedList<>();
//        scopes.add("https://graph.microsoft.com/.default");
//
//        TokenCredentialAuthProvider tokenCredentialAuthProvider =
//                new TokenCredentialAuthProvider(scopes, clientSecretCredential);
//
//        this.graphClient = GraphServiceClient
//                .builder()
//                .authenticationProvider(tokenCredentialAuthProvider)
//                .buildClient();
//    }

//    public AzureADService() {
//        IAuthenticationProvider authProvider = requestUrl -> {
//            OAuth2AuthenticationToken authentication = (OAuth2AuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
//
//            OAuth2AuthenticatedPrincipal principal = authentication.getPrincipal();
//            String accessToken = principal.getAttribute("access_token");
//            return CompletableFuture.completedFuture("Bearer " + accessToken);
//        };
//
//        this.graphClient = GraphServiceClient
//                .builder()
//                .authenticationProvider(authProvider)
//                .buildClient();
//    }


    public String someMethod(){
        return TokenManager.getInstance().getAccessToken();
    }

    private GraphServiceClient getGraphClient() {
        if (graphClient == null) {
            String accessToken = TokenManager.getInstance().getAccessToken();
            log.info("accessToken: {}", accessToken);

            if (accessToken == null) {
                throw new RuntimeException("Access token not found");
            }

            log.info("access token: {}", accessToken);

            this.graphClient = GraphServiceClient
                    .builder()
                    .authenticationProvider(requestUrl -> CompletableFuture.completedFuture("Bearer " + accessToken))
                    .buildClient();
        }
        return graphClient;
    }

    public User getMyProfile() {
        try {
            return getGraphClient().me().buildRequest().get();
        } catch (Exception e) {
            System.err.println("Error fetching profile: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    public List<User> fetchAllUsers() {
        try {
            return getGraphClient().users()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error fetching users: " + e.getMessage());
        }
    }


    public User createUser(String displayName, String userPrincipalName, String password) {
        try {
            User newUser = new User();
            newUser.accountEnabled = true;
            newUser.displayName = displayName;
            newUser.mailNickname = displayName;
            newUser.userPrincipalName = userPrincipalName;

            PasswordProfile passwordProfile = new PasswordProfile();
            passwordProfile.password = password;
            passwordProfile.forceChangePasswordNextSignIn = true;
            newUser.passwordProfile = passwordProfile;

            return graphClient.users().buildRequest().post(newUser);
        } catch (Exception e) {
            System.err.println("Error creating user: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error creating user: " + e.getMessage());
        }
    }

    public void assignRole(String userId, String roleTemplateId) {
        try {
            // Fetch all directory roles
            List<DirectoryRole> roles = graphClient.directoryRoles()
                    .buildRequest()
                    .get()
                    .getCurrentPage();

            // Find the specific directory role with the matching roleTemplateId
            DirectoryRole matchedRole = roles.stream()
                    .filter(role -> role.roleTemplateId.equals(roleTemplateId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Role with the specified roleTemplateId not found"));

            // Create directoryObject reference for the user to be added to the role
            DirectoryObject userDirectoryObject = new DirectoryObject();
            userDirectoryObject.id = userId;

            // Add the user to the role
            graphClient.directoryRoles(matchedRole.id)
                    .members()
                    .references()
                    .buildRequest()
                    .post(userDirectoryObject);

            System.out.println("User assigned to role successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error assigning role: " + e.getMessage());
        }
    }


    public Group createGroup(String displayName, String mailNickname) {
        try {
            Group newGroup = new Group();
            newGroup.displayName = displayName;
            newGroup.mailEnabled = false;
            newGroup.mailNickname = mailNickname;
            newGroup.securityEnabled = true;

            return graphClient.groups().buildRequest().post(newGroup);
        } catch (Exception e) {
            System.err.println("Error creating group: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error creating group: " + e.getMessage());
        }
    }

    public void addUserToGroup(String groupId, String userId) {
        try {
            DirectoryObject user = new DirectoryObject();
            user.id = userId;

            graphClient.groups(groupId)
                    .members()
                    .references()
                    .buildRequest()
                    .post(user);
        } catch (Exception e) {
            System.err.println("Error adding user to group: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error adding user to group: " + e.getMessage());
        }
    }

    public List<DirectoryRoleTemplate> getRoleTemplates() {
        try {
            return graphClient.directoryRoleTemplates()
                    .buildRequest()
                    .get()
                    .getCurrentPage();
        } catch (Exception e) {
            System.err.println("Error fetching role templates: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    public DirectoryRole createRole(String roleTemplateId, String roleName, String description) {
        try {
            DirectoryRole newRole = new DirectoryRole();
            newRole.displayName = roleName;
            newRole.description = description;

            return graphClient.directoryRoles()
                    .buildRequest()
                    .post(newRole);
        } catch (Exception e) {
            System.err.println("Error creating role: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }


}
