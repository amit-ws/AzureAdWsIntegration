package com.ws.controller;

import com.microsoft.graph.models.*;
import com.microsoft.graph.requests.*;
import com.ws.cofiguration.GraphServiceClientFactory;
import com.ws.service.TokenManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@Slf4j
public class GroupController {

//    private final GraphServiceClient graphClient;

//    public GroupController() {
//        ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
//                .clientId("9acacaf6-02e1-4e06-84d9-5da4a7ffd2aa")
//                .clientSecret("sJB8Q~G-YDCgTRPv6J~LZCQkNyDyUATwQvP_Bcx0")
//                .tenantId("00b1d06b-e316-45af-a6d2-2734f62a5acd")
//                .build();
//
//        IAuthenticationProvider authProvider = requestUrl -> {
//            TokenRequestContext requestContext = new TokenRequestContext()
//                    .addScopes("https://graph.microsoft.com/.default");
//
//            return CompletableFuture.supplyAsync(() -> {
//                try {
//                    String at = clientSecretCredential.getToken(requestContext).block().getToken();
//                    return at;
//                } catch (Exception e) {
//                    throw new RuntimeException("Failed to get token", e);
//                }
//            });
//        };
//
//        this.graphClient = GraphServiceClient
//                .builder()
//                .authenticationProvider(authProvider)
//                .buildClient();
//    }

//    public GroupController() {
//        this.graphClient = GraphServiceClient
//                .builder()
//                .authenticationProvider(requestUrl -> {
//                    try {
//                        return CompletableFuture.completedFuture(TokenManager.getInstance().getApplicationAccessToken());
//                    } catch (ExecutionException | InterruptedException e) {
//                        throw new RuntimeException("Failed to get access token", e);
//                    }
//                })
//                .buildClient();
//    }


    @GetMapping("/api/groups")
    public List<Group> getGroups() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        GroupCollectionPage groups = graphClient.groups()
                .buildRequest()
                .get();

        return groups.getCurrentPage();
    }

    @PostMapping("/api/groups")
    public Group createGroup(@RequestBody Group group) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        Group result = graphClient.groups()
                .buildRequest()
                .post(group);
        return result;
    }

    @GetMapping("/api/users")
    public UserCollectionPage getUsers() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        UserCollectionPage result = graphClient.users()
                .buildRequest()
                .get();
        return result;
    }

    @PostMapping("/api/users")
    public User createUser(@RequestBody User user) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        User result = graphClient.users()
                .buildRequest()
                .post(user);
        return result;
    }


    @GetMapping("/api/devices")
    public DeviceCollectionPage getDevices() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DeviceCollectionPage result = graphClient.devices()
                .buildRequest()
                .get();
        return result;
    }


    /**
     * Endpoint: GET https://graph.microsoft.com/v1.0/devices/{deviceId}
     *
     * @param deviceId
     * @return
     */
    @GetMapping("/api/getDeviceById")
    public Device getDeviceById(@RequestParam String deviceId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        Device result = graphClient.devices(deviceId)
                .buildRequest()
                .get();
        return result;
    }

    /**
     * Endpoint: GET https://graph.microsoft.com/v1.0/users/{userId}/registeredDevices
     *
     * @param userId
     * @return
     */
    @GetMapping("/api/users/registeredDevices")
    public DirectoryObjectCollectionWithReferencesPage getUserRegisteredDevices(@RequestParam String userId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DirectoryObjectCollectionWithReferencesPage result = graphClient.users(userId)
                .registeredDevices()
                .buildRequest()
                .get();
        return result;
    }


    @PostMapping("/api/devices")
    public Device createDevices(@RequestBody Device device) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        return graphClient.devices()
                .buildRequest()
                .post(device);
    }

//    @GetMapping("/api/tenant")
//    public Organization getTenantDetails() {
//        String[] selectFields = new String[]{
//                "id",
//                "displayName",
//                "countryLetterCode",
//                "verifiedDomains",
//                "marketingNotificationEmails"
//        };
//        OrganizationCollectionPage tenantPage = graphClient.organization()
//                .buildRequest()
//                .select(String.join(",", selectFields))
//                .get();
//
//        if (!tenantPage.getCurrentPage().isEmpty()) {
//            return tenantPage.getCurrentPage().get(0);
//        }
//
//        return null;
//    }


    @GetMapping("/api/tenant")
    public List<Organization> getTenant() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        OrganizationCollectionPage tenantPage = graphClient.organization()
                .buildRequest()
                .get();
        return tenantPage.getCurrentPage();
    }


    @GetMapping("/api/emails")
    public MessageCollectionPage listEmails() {
        if (StringUtils.isEmpty(TokenManager.getInstance().getAccessToken())) {
            throw new RuntimeException("Please login first as user @ Azure");
        }
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient("DELEGATED");

//        MessageCollectionPage messagePage = graphClient.me()
//                .messages()
//                .buildRequest()
//                .select("sender,subject")
//                .get();

        MessageCollectionPage messagePage = graphClient.me()
                .messages()
                .buildRequest()
                .get();

        return messagePage;
    }


    /**
     * Fetches the list of all associated groups of the User
     */
    @GetMapping("/api/getGroupsOfUser")
    public List<DirectoryObject> getGroupsOfUser(@RequestParam String userId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DirectoryObjectCollectionWithReferencesPage groups = graphClient.users(userId)
                .memberOf()
                .buildRequest()
                .get();
        return new ArrayList<>(groups.getCurrentPage());
    }


    @GetMapping("/api/getMembersOfGroup")
    public List<DirectoryObject> getMembersOfGroup(@RequestParam String groupId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DirectoryObjectCollectionWithReferencesPage members = graphClient.groups(groupId)
                .members()
                .buildRequest()
                .get();
        return new ArrayList<>(members.getCurrentPage());
    }

    /**
     * Retrieves the list of all AVAILABLE ROLES provided by the Microsoft under Azure -Ad
     * Hence these aren't the user defined roles
     * These are pre-defined Azure-Ad roles
     */
    @GetMapping("/api/roleDefinitions")
    public List<UnifiedRoleDefinition> getRoleDefinitions() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        UnifiedRoleDefinitionCollectionPage roleDefinitions = graphClient.roleManagement()
                .directory()
                .roleDefinitions()
                .buildRequest()
                .get();
        return new ArrayList<>(roleDefinitions.getCurrentPage());
    }


    /**
     * Retrieves the list of Roles -- with -- User assigned to it
     * Roles: Those Azure-Ad predefined roles
     */
    @GetMapping("/api/roleAssignments")
    public List<UnifiedRoleAssignment> getRoleAssignments() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        UnifiedRoleAssignmentCollectionPage roleAssignments = graphClient.roleManagement()
                .directory()
                .roleAssignments()
                .buildRequest()
                .get();
        return new ArrayList<>(roleAssignments.getCurrentPage());
    }


    /**
     * Retrieves the list of all custom defined APP ROLES under the Application (Client)
     * applicationId: use the Object id not the original client_id
     */
    @GetMapping("/api/getAppRoles")
    public List<AppRole> getAppRoles(@RequestParam String applicationId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        Application application = graphClient.applications(applicationId)
                .buildRequest()
                .get();
        return application.appRoles;
    }


    /**
     * Retrieves all the applications from the Tenant
     */
    @GetMapping("/api/applications")
    public List<Application> getAllApplications() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);

        ApplicationCollectionPage applicationCollection = graphClient.applications()
                .buildRequest()
                .get();
        return applicationCollection.getCurrentPage();
    }


    /**
     * Retrieves all the member users of group
     */
    @GetMapping("/api/getMemebersOfGroup")
    public List<DirectoryObject> getMemebersOfGroup(@RequestParam String groupId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DirectoryObjectCollectionWithReferencesPage members = graphClient.groups(groupId)
                .members()
                .buildRequest()
                .get();
        return new ArrayList<>(members.getCurrentPage());
    }


    /**
     * Retrieves groups with which a user is associated with
     */
    @GetMapping("/api/getGroupsOfUsers")
    public List<DirectoryObject> getGroupsOfUsers(@RequestParam String userId) {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        DirectoryObjectCollectionWithReferencesPage members = graphClient.users(userId)
                .memberOf()
                .buildRequest()
                .get();
        List<DirectoryObject> groups = members.getCurrentPage().stream()
                .filter(member -> "#microsoft.graph.group".equals(member.oDataType))
                .collect(Collectors.toList());
        return groups;
    }


    /**
     * List all Service principles
     */
    @GetMapping("/api/listServicePrinciples")
    public List<ServicePrincipal> getGroupsOfUsers() {
        final GraphServiceClient graphClient = GraphServiceClientFactory.createClient(null);
        List<ServicePrincipal> servicePrincipals = graphClient
                .servicePrincipals()
                .buildRequest()
                .get()
                .getCurrentPage();
        return servicePrincipals;
    }

}




