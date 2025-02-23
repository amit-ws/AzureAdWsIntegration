package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.PublicIpAddressSku;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.containerservice.models.CredentialResult;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.containerservice.models.KubernetesClusters;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.azure.resourcemanager.network.models.NicIpConfiguration;
import com.azure.resourcemanager.network.models.PublicIpAddress;
import com.azure.resourcemanager.resources.models.PolicyAssignment;
import com.azure.resourcemanager.resources.models.PolicyDefinition;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.resources.models.Subscription;
import com.azure.resourcemanager.sql.models.ServerPrivateEndpointConnection;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.configuration.AzureAuthConfigurationFactory;
import com.ws.azureResourcesIntegration.dto.*;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.micrometer.common.util.StringUtils;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourcesTestService {
    final String clientId2 = "f741d2f8-8ec5-4246-9051-96fd8f041267";
    final String clientSecret2 = "C6n8Q~Pe3lYUXaRp6gLNOZUK~uM5UUSkqP~9JbuY";
    final String tenantId2 = "0079de83-6146-45cb-a189-5d5b03507ce8";
    final String subscriptionId2 = "15b85f1d-1983-469c-a593-46fe8fc514f7";
    final String clientId = "cb51e8d1-519c-4e18-9b2f-28d53e6badd1";
    final String clientSecret = "3F18Q~iM8DjCXg7rL~2.BZZPtdGNAzfOf2qXRdhC";
    final String tenantId = "f875ebf8-f5f0-4915-a2c9-4442e0118fd2";
    final String subscriptionId = "4769af8e-ca3d-448d-bd1a-80e03ed94158";
    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
    final RestTemplate restTemplate;

    @Autowired
    public AzureResourcesTestService(AzureAuthConfigurationFactory azureAuthConfigurationFactory, RestTemplate restTemplate) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
        this.restTemplate = restTemplate;
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    public Collection<VmDTO> listVMs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachine> vms = azureResourceManager.virtualMachines().list();
        log.info("total: {}", vms.stream().toList().size());
//        return StreamSupport.stream(vms.spliterator(), false)
//                .map(vm -> VmDTO.builder()
//                        .vmId(vm.vmId())
//                        .instanceId(vm.id())
//                        .name(vm.name())
//                        .computerName(vm.computerName())
////                        .powerState(vm.powerState().toString())
////                        .size(vm.size().getValue())
//                        .osType(vm.osType().toString())
//                        .publicIPInstanceId(vm.getPrimaryPublicIPAddressId())
//                        .resourceGroupName(vm.resourceGroupName())
//                        .osDiskSize(vm.osDiskSize())
//                        .region(vm.region().name())
////                        .securityType(vm.securityType().toString())
//                        .type(vm.type())
////                        .zones(vm.innerModel().zones())
//                        .resourceIdentityType(vm.innerModel().identity() != null ? vm.innerModel().identity().type().name() : null)
//                        .ipAddress(vm.getPrimaryPublicIPAddress().ipAddress())
//                        .build())
//                .collect(Collectors.toList());
        return Collections.emptyList();
    }

    public Collection<ResourceGroupDTO> listResourceGroups() {
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        PagedIterable<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list();
        return StreamSupport.stream(resourceGroups.spliterator(), false)
                .map(resourceGroup -> ResourceGroupDTO.builder()
                        .id(resourceGroup.innerModel().id())
                        .name(resourceGroup.name())
                        .regionName(resourceGroup.regionName())
                        .build())
                .collect(Collectors.toList());
    }


    public Collection<StorageAccountDTO> listStorageAccounts() {
        List<StorageAccountDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        log.info("total SA: {}", azureResourceManager.storageAccounts().list().stream().toList().size());
        azureResourceManager.storageAccounts().list().forEach(storageAccount -> {
            azureResourceManager.storageBlobContainers()
                    .list(storageAccount.resourceGroupName(), storageAccount.name())
                    .forEach(container -> {
                        StorageAccountDTO storageDetails = StorageAccountDTO.builder()
                                .storageAccountId(storageAccount.id())
                                .storageAccountName(storageAccount.name())
                                .region(storageAccount.region().toString())
                                .createdDate(storageAccount.creationTime())
                                .kind(GenericUtil.getOrNull(() -> storageAccount.kind().toString()))
                                .customDomainName(storageAccount.customDomain() != null ? storageAccount.customDomain().name() : null)
                                .blobPublicAccessAllowed(storageAccount.isBlobPublicAccessAllowed())
                                .sharedKeyAccessAllowed(storageAccount.isSharedKeyAccessAllowed())
                                .isAccessAllowedFromAllNetworks(storageAccount.isAccessAllowedFromAllNetworks())
                                .publicAccess(GenericUtil.getOrNull(() -> storageAccount.publicNetworkAccess().toString()))
                                .containerName(container.name())
                                .publicAccess(container.publicAccess() != null ? container.publicAccess().toString() : null)
                                .containerType(container.type() != null ? container.type().toString() : null) // Handle null container type
                                .build();
                        response.add(storageDetails);
                    });
        });
        return response;
    }


    public Collection<RoleDefinitionDTO> listRBACRoles() {
        List<RoleDefinitionDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleDefinition> roles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        roles.forEach(role -> {
            List<PermissionDTO> permissions = new ArrayList<>();
            if (role.permissions() != null) {
                role.permissions().forEach(permission -> {
                    permissions.add(PermissionDTO.builder()
                            .actions(permission.actions())
                            .notActions(permission.notActions())
                            .build());
                });
            }
            response.add(RoleDefinitionDTO.builder()
                    .roleId(role.id())
                    .name(role.name())
                    .roleName(role.roleName())
                    .description(role.description())
                    .isCustomRole(isCustomRole(role.innerModel().roleType()))
                    .permissions(permissions)
                    .type(role.type())
                    .roleType(role.innerModel().roleType())
                    .assignableScopes(role.assignableScopes())
                    .createdBy(role.innerModel().createdBy())
                    .build());
        });
        return response;
    }


    public Collection<RoleAssignmentDTO> listRoleAssignments() {
        List<RoleAssignmentDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        assignments.forEach(assignment -> {
            RoleAssignmentDTO roleAssignmentDTO = RoleAssignmentDTO.builder()
                    .roleAssignmentId(assignment.id())
                    .assignedRoleDefinitionId(assignment.roleDefinitionId())
                    .name(assignment.name())
                    .description(assignment.description())
                    .assignee(assignment.principalId())
                    .scope(assignment.scope())
                    .condition(assignment.condition())
                    .createdBy(assignment.innerModel().createdBy())
                    .type(assignment.innerModel().type())
                    .principalType(assignment.innerModel().principalType().toString())
                    .build();
            response.add(roleAssignmentDTO);
        });
        return response;
    }


    public List<DBServerDTO> listAllServerWithDBsForTenant() {
        List<DBServerDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        for (SqlServer sqlServer : azureResourceManager.sqlServers().list()) {
            log.info("Kind: {}", sqlServer.kind());
            log.info("State: {}", sqlServer.state());
            log.info("Managed Service Identity Enabled: {}", sqlServer.isManagedServiceIdentityEnabled());
            log.info("Managed Service Identity Type: {}", sqlServer.managedServiceIdentityType());
            log.info("Public Network Access: {}", sqlServer.publicNetworkAccess());
            log.info("Location: {}", sqlServer.innerModel().location());
            log.info("Administrator Login: {}", sqlServer.administratorLogin());
            if (sqlServer.getActiveDirectoryAdministrator() != null) {
                log.info("Administrator ID: {}", sqlServer.getActiveDirectoryAdministrator().id());
                log.info("Administrator Type: {}", sqlServer.getActiveDirectoryAdministrator().administratorType());
                log.info("Administrator Sign-in Name: {}", sqlServer.getActiveDirectoryAdministrator().signInName());
            }
            log.info("DNS Aliases: {}", sqlServer.dnsAliases());
            log.info("Resource Group Name: {}", sqlServer.resourceGroupName());
            log.info("Version: {}", sqlServer.version());
            log.info("Inner Model State: {}", sqlServer.innerModel().state());
            if (sqlServer.innerModel().privateEndpointConnections() != null) {
                for (ServerPrivateEndpointConnection privateEndpointConnection : sqlServer.innerModel().privateEndpointConnections()) {
                    log.info("Private Endpoint Connection ID: {}", privateEndpointConnection.id());
                    log.info("Private Endpoint ID: {}", privateEndpointConnection.properties().privateEndpoint().id());
                }
            }
            log.info(" ");
            for (SqlDatabase sqlDatabase : sqlServer.databases().list()) {
                log.info("Edition: {}", sqlDatabase.edition());
                log.info("Max Size (Bytes): {}", sqlDatabase.maxSizeBytes());
                log.info("Region: {}", sqlDatabase.region());
                log.info("Status: {}", sqlDatabase.innerModel().status().toString());
                log.info("Read Scale: {}", sqlDatabase.innerModel().readScale().toString());
                log.info("Min Capacity: {}", sqlDatabase.innerModel().minCapacity());
                log.info("Paused Date: {}", sqlDatabase.innerModel().pausedDate());
                log.info("Resumed Date: {}", sqlDatabase.innerModel().resumedDate());
                log.info("DefaultSecondaryLocation: {}", sqlDatabase.defaultSecondaryLocation());
                log.info("location: {}", sqlDatabase.innerModel().location());
            }
        }
        azureResourceManager.sqlServers().list().forEach(sqlServer -> {
            DBServerDTO serverDTO = DBServerDTO.builder()
                    .serverId(sqlServer.id())
                    .serverName(sqlServer.name())
                    .type(sqlServer.type())
                    .region(sqlServer.region().name())
                    .serverVersion(sqlServer.version())
                    .kind(sqlServer.kind())
                    .state(sqlServer.state())
                    .managedServiceIdentityEnabled(sqlServer.isManagedServiceIdentityEnabled())
                    .managedServiceIdentityType(sqlServer.managedServiceIdentityType().toString())
                    .publicNetworkAccess(sqlServer.publicNetworkAccess().toString())
                    .resourceGroupName(sqlServer.resourceGroupName())
                    .innerModelState(sqlServer.innerModel().state())
                    .administratorType(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().administratorType().toString() : null)
                    .administratorSignInName(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().signInName() : null)
                    .administratorId(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().id() : null)
                    .privateEndpointConnectionIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(ServerPrivateEndpointConnection::id)
                            .collect(Collectors.toList()) : null)
                    .privateEndpointIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(connection -> connection.properties().privateEndpoint().id())
                            .collect(Collectors.toList()) : null)
                    .databases(sqlServer.databases().list().stream()
                            .map(sqlDatabase -> DatabaseDTO.builder()
                                    .databaseId(sqlDatabase.databaseId())
                                    .databaseName(sqlDatabase.name())
                                    .databaseType(sqlDatabase.innerModel().type())
                                    .status(sqlDatabase.status().toString())
                                    .dbStatus(sqlDatabase.innerModel().status().toString())
                                    .edition(sqlDatabase.edition().toString())
                                    .maxSizeBytes(sqlDatabase.maxSizeBytes())
                                    .region(sqlDatabase.region().toString())
                                    .readScale(sqlDatabase.innerModel().readScale().toString())
                                    .minCapacity(sqlDatabase.innerModel().minCapacity())
                                    .pausedDate(sqlDatabase.innerModel().pausedDate())
                                    .resumedDate(sqlDatabase.innerModel().resumedDate())
                                    .build())
                            .collect(Collectors.toList()))
                    .build();

            response.add(serverDTO);
        });
        return response;
    }

    public List<DBServerDTO> getServers() {
        List<DBServerDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        azureResourceManager.sqlServers().list().forEach(sqlServer -> {
            DBServerDTO serverDTO = DBServerDTO.builder()
                    .serverId(sqlServer.id())
                    .serverName(sqlServer.name())
                    .type(sqlServer.type())
                    .region(sqlServer.region().name())
                    .serverVersion(sqlServer.version())
                    .kind(sqlServer.kind())
                    .state(sqlServer.state())
                    .managedServiceIdentityEnabled(sqlServer.isManagedServiceIdentityEnabled())
                    .managedServiceIdentityType(sqlServer.managedServiceIdentityType().toString())
                    .publicNetworkAccess(sqlServer.publicNetworkAccess().toString())
                    .resourceGroupName(sqlServer.resourceGroupName())
                    .innerModelState(sqlServer.innerModel().state())
                    .administratorType(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().administratorType().toString() : null)
                    .administratorSignInName(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().signInName() : null)
                    .administratorId(sqlServer.getActiveDirectoryAdministrator() != null
                            ? sqlServer.getActiveDirectoryAdministrator().id() : null)
                    .privateEndpointConnectionIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(ServerPrivateEndpointConnection::id)
                            .collect(Collectors.toList()) : null)
                    .privateEndpointIds(sqlServer.innerModel().privateEndpointConnections() != null
                            ? sqlServer.innerModel().privateEndpointConnections().stream()
                            .map(connection -> connection.properties().privateEndpoint().id())
                            .collect(Collectors.toList()) : null)
                    .location(sqlServer.innerModel().location())
                    .administratorLogin(sqlServer.administratorLogin())
                    .build();

            response.add(serverDTO);
        });
        return response;
    }


    public List<SubscriptionDTO> listSubscriptions() {
        List<SubscriptionDTO> response = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        PagedIterable<Subscription> subscriptions = azureResourceManager.subscriptions().list();
        subscriptions.forEach(subscription -> {
            SubscriptionDTO subscriptionDTO = SubscriptionDTO.builder()
                    .subscriptionId(subscription.subscriptionId())
                    .subscriptionName(subscription.displayName())
                    .subscriptionState(subscription.state().name())
                    .spendingLimit(subscription.subscriptionPolicies().spendingLimit().name())
                    .build();
            response.add(subscriptionDTO);
        });
        return response;
    }


    public Map<String, String> listK8Clusters() {
//        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId2, clientSecret2, tenantId2, subscriptionId2);
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<KubernetesCluster> aksClusters = azureResourceManager.kubernetesClusters().list();
        Map<String, String> clusterDetailsMap = new HashMap<>();

        log.info("total: {}", aksClusters.stream().count());

        for (KubernetesCluster k8Cluster : aksClusters) {
            clusterDetailsMap.put("ID", k8Cluster.id());
            clusterDetailsMap.put("Cluster Name", k8Cluster.name());
            clusterDetailsMap.put("Region", k8Cluster.regionName());
            clusterDetailsMap.put("Resource Group", k8Cluster.resourceGroupName());
            clusterDetailsMap.put("Kubernetes Version", k8Cluster.version());
            clusterDetailsMap.put("Region name", k8Cluster.region().name());
            clusterDetailsMap.put("Region label", k8Cluster.region().label());
            clusterDetailsMap.put("Service Principal Client ID", k8Cluster.servicePrincipalClientId());
            clusterDetailsMap.put("Disk Encryption Set ID", k8Cluster.diskEncryptionSetId());
            clusterDetailsMap.put("Power State", GenericUtil.getOrNull(() -> k8Cluster.powerState().code().getValue()));
            clusterDetailsMap.put("Provisioning State", k8Cluster.provisioningState());
            clusterDetailsMap.put("Type", GenericUtil.getOrNull(k8Cluster::type));
            clusterDetailsMap.put("SKU name", GenericUtil.getOrNull(() -> k8Cluster.sku().name().getValue()));
            clusterDetailsMap.put("SKU tier", GenericUtil.getOrNull(() -> k8Cluster.sku().tier().getValue()));
            clusterDetailsMap.put("Is Azure RBAC Enabled", String.valueOf(k8Cluster.isAzureRbacEnabled()));
            clusterDetailsMap.put("Public Network Access", GenericUtil.getOrNull(() -> k8Cluster.publicNetworkAccess().getValue()));
            clusterDetailsMap.put("Node Resource Group", k8Cluster.nodeResourceGroup());
            clusterDetailsMap.put("Is Local Accounts Enabled", String.valueOf(k8Cluster.isLocalAccountsEnabled()));
            clusterDetailsMap.put("ManagedClusterIdentity type", GenericUtil.getOrNull(() -> k8Cluster.innerModel().identity().type().name()));

//            clusterDetailsMap.put("Tags", GenericUtil.getOrNull(() -> k8Cluster.tags().values()));
            clusterDetailsMap.put("Network Profile", GenericUtil.getOrNull(() -> k8Cluster.networkProfile().toString()));

        }

        return clusterDetailsMap;
    }


    /**
     * CHECK:
     * 1. Does this token belongs to the Application level itself because there is no use of a azure-user in here
     * 2. Is it alternative of getting token of Azure AD SSO (here we are bypassing the login flow)
     */
    public String generateConsoleURL() {
        try {
            ConfidentialClientApplication app = ConfidentialClientApplication.builder(
                            clientId,
                            ClientCredentialFactory.createFromSecret(clientSecret))
                    .authority("https://login.microsoftonline.com/" + tenantId)
                    .build();

            ClientCredentialParameters parameters = ClientCredentialParameters.builder(
                            Collections.singleton("https://management.azure.com/.default"))
                    .build();

            CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
            IAuthenticationResult result = future.get();
            String token = result.idToken();

            long expirationTime = System.currentTimeMillis() / 1000 + 3600;

            return "https://portal.azure.com?login_hint=" + "amit@whiteswansecurity.com" + "&sessionIdToken=" + result.idToken() + "&exp=" + expirationTime;

//            return "https://portal.azure.com/?token=" + result.accessToken() + "&exp=" + expirationTime;
        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
        }
    }


    public String getAzureFederatedSSOUrl(String userPrincipalName, String code) throws Exception {
        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/token";

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("resource", "https://management.azure.com/");
        body.add("code", code);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.exchange(tokenUrl, HttpMethod.POST, request, Map.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Failed to get access token");
        }

        String accessToken = (String) response.getBody().get("access_token");

        // Step 2: Exchange this token for a session token
        String s = "https://portal.azure.com/?token=" + accessToken;

        String federatedTokenUrl = "https://portal.azure.com/" + tenantId + "/federation?user=" + userPrincipalName + "&token=" + accessToken;

        return federatedTokenUrl;
    }


    public void listPublicIPAddressInstances() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<PublicIpAddress> publicIpAddresses = azureResourceManager.publicIpAddresses().listByResourceGroup("qa10_group");
        for (PublicIpAddress publicIpAddress : publicIpAddresses) {
            log.info("ID: {}", publicIpAddress.id());
            log.info("IP address: {}", publicIpAddress.ipAddress());
            log.info("IP address: {}", publicIpAddress.name());
            log.info("IP address: {}", publicIpAddress.version());
            log.info("IP address: {}", publicIpAddress.region());
            log.info("IP address: {}", publicIpAddress.hasAssignedNetworkInterface());
            log.info("IP address: {}", publicIpAddress.hasAssignedLoadBalancer());
            log.info("IP address: {}", publicIpAddress.innerModel().type());
            log.info("IP address: {}", publicIpAddress.ipAllocationMethod().toString());
        }
    }


    /**
     * GOAL: To determine which NIC of the target VM has any number of public IP address
     */
    public List<String> determineNicWithPublicIpAddressForVM(String rgName, String vmName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        VirtualMachine virtualMachine = azureResourceManager.virtualMachines().getByResourceGroup(rgName, vmName);
        if (virtualMachine == null) {
            throw new RuntimeException("VM not found!");
        }
        // Fetch all the NICs Objects for the VM
        List<NetworkInterface> networkInterfaces = virtualMachine.networkInterfaceIds().stream()
                .map(nicId -> azureResourceManager.networkInterfaces().getById(nicId))
                .toList();

        // Iterate to determine which NIC has at least one public IP address. Store the NIC instance path ID of such NIC
        List<String> foundNICPatD = new ArrayList<>();
        for (NetworkInterface networkInterface : networkInterfaces) {
            for (Map.Entry<String, NicIpConfiguration> stringNicIpConfigurationEntry : networkInterface.ipConfigurations().entrySet()) {
                NicIpConfiguration nicIpConfiguration = stringNicIpConfigurationEntry.getValue();

                String publicPathId = nicIpConfiguration.publicIpAddressId(); // Its the azure full path ID for the IP-Address object 1️⃣

                if (StringUtils.isNotEmpty(publicPathId)) {
                    PublicIpAddress publicIpAddress = nicIpConfiguration.getPublicIpAddress();
                    publicIpAddress.id(); // Its the azure full path ID for the IP-Address object 1️⃣

                    if (!ObjectUtils.isEmpty(publicIpAddress)) {
                        log.info("Following NIC has the public IP address: {}", networkInterface.id()); // Target NIC found which has at least one public IP
                        log.info("Public IP address instance path ID: {}", publicIpAddress.id()); // Target NIC found which has at least one public IP
                        log.info("Public IP Found: {}", publicIpAddress.ipAddress());
                        foundNICPatD.add(networkInterface.id());
                        break;
                    } else {
                        log.warn("Public IP ID exists, but could not fetch details. Reason: {} {}", "IP address DELETED", "PERMISSION issues");
                    }
                } else {
                    log.error("No Public IP assigned to this NIC configuration");
                }
            }
            if (CollectionUtils.isEmpty(foundNICPatD)) {
                throw new RuntimeException("NO Public NIC found for the provided VM");
            }
        }
        log.info("-------------------------");
        log.info("Total NIC with public IP addresses: {}", foundNICPatD.size());
        log.info("NIc path ID with public IP addresses: {}", foundNICPatD);

        return foundNICPatD;
    }




    /* -------------------------------------------------------------------------------------------------------------- */
    /* -------------------------------------------------------------------------------------------------------------- */


    /*
    select * from aws_roles; -- → Azure Role Assignments
    select * from aws_role_attached_permissions;  -- → Permissions from Built-in Role Definitions
    select * from aws_role_in_line_permissions; -- → Permissions from Custom Role Definitions

    select * from aws_policy;  -- → Azure Role Definitions (includes both Built-in and Custom)
    select * from aws_attached_policies; -- → Built-in Role Definitions assigned via Role Assignments
    select * from aws_inline_policies; -- → Custom Role Definitions assigned via Role Assignments
    * */
    public void experiments() {
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);

        azureResourceManager.identities().list();
        azureResourceManager.accessManagement().servicePrincipals().list();
    }


    public List<IdentityDTO> getIdentities() {
        List<IdentityDTO> identities = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        azureResourceManager.identities().list().forEach((identity -> {
            IdentityDTO dto = IdentityDTO.builder()
                    .id(identity.id())
                    .clientId(identity.clientId())
                    .tenantId(identity.tenantId())
                    .principalId(identity.principalId())
                    .type(identity.innerModel().type())
                    .name(identity.innerModel().name())
                    .build();
            identities.add(dto);
        }));
        return identities;
    }


    public List<ServicePrincipleDTO> getServicePrinciples() {
        List<ServicePrincipleDTO> identities = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        azureResourceManager.accessManagement().servicePrincipals().list().forEach((servicePrincipal -> {
            ServicePrincipleDTO dto = ServicePrincipleDTO.builder()
                    .id(servicePrincipal.id())
                    .applicationId(servicePrincipal.applicationId())
                    .servicePrincipalNames(servicePrincipal.servicePrincipalNames())
                    .build();
            identities.add(dto);
        }));
        return identities;
    }


    public void printInlineRolePermissions(AzureResourceManager azureResourceManager, String subscriptionId) {
        try {
            System.out.println("Fetching Custom Role Definitions (equivalent to AWS Inline Role Permissions)...");

            // Step 1: Get all Role Definitions (Custom Roles)
            azureResourceManager.accessManagement()
                    .roleDefinitions()
                    .listByScope("/subscriptions/" + subscriptionId)
                    .stream()
                    .filter(roleDefinition -> roleDefinition.innerModel().roleType().equalsIgnoreCase("CustomRole")) // Custom Roles only
                    .forEach(roleDefinition -> {
                        System.out.println("-----------------------------------------------------");
                        System.out.println("Role Definition ID: " + roleDefinition.id());
                        System.out.println("Role Name: " + roleDefinition.roleName());
                        System.out.println("Role Description: " + roleDefinition.description());
                        System.out.println("Permissions: ");

                        roleDefinition.permissions().forEach(permission -> {
                            System.out.println("  Actions: " + permission.actions());
                            System.out.println("  NotActions: " + permission.notActions());
                            System.out.println("  DataActions: " + permission.dataActions());
                            System.out.println("  NotDataActions: " + permission.notDataActions());
                        });
                    });

            // Step 2: Get Role Assignments for these Custom Roles
            System.out.println("\nFetching Role Assignments for Custom Roles...");
            azureResourceManager
                    .accessManagement()
                    .roleAssignments()
                    .listByScope("/subscriptions/" + subscriptionId)
                    .forEach(roleAssignment -> {
                        System.out.println("-----------------------------------------------------");
                        System.out.println("Role Assignment ID: " + roleAssignment.id());
                        System.out.println("Role Definition ID (Linked Role): " + roleAssignment.roleDefinitionId());
                        System.out.println("Assigned Principal ID: " + roleAssignment.principalId());
                        System.out.println("Scope: " + roleAssignment.scope());
                    });
        } catch (Exception e) {
            System.out.println("An error occurred while fetching the role permissions: " + e.getMessage());
        }
    }


    /**
     * LIST ALL ROLE-DEFINITIONS (UNIQUE)
     * 1. Unique: ones those who got inherited by the child hierarchy
     * 2. Another which was created for a parent and also a child
     * Eg:
     * Role: rolr-1
     * scopes: [parent-1, children-1]
     */
    public void fetchRoleDefinitionEntity() {
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);

        /* FETCHING AT SUBSCRIPTION LEVEL (includes Roles inherited from top hierarchies + created specifically for Subscription as well) */
        PagedIterable<RoleDefinition> subsRoles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        Set<RoleDefinition> roleDefinitionSet = subsRoles.stream().collect(Collectors.toSet());

        /* FETCHING AT EACH RESOURCE-GROUP LEVEL ONLY */
        PagedIterable<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list();
        resourceGroups.forEach((resourceGroup -> {
            PagedIterable<RoleDefinition> rgRoles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s/resourceGroups/%s", subscriptionId, resourceGroup.name()));
            Set<RoleDefinition> rgRoleDefinitionSet = rgRoles.stream()
                    .filter(rgRole -> rgRole.innerModel().roleType().equals("CustomRole") && rgRole.assignableScopes().contains(String.format("/subscriptions/%s/resourceGroups/%s", subscriptionId, resourceGroup.name())))
                    .collect(Collectors.toSet());
            roleDefinitionSet.addAll(rgRoleDefinitionSet);
        }));
    }


    public List<PolicyDefinitionDto> listPolicyDefinition() {
        List<PolicyDefinitionDto> policies = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        PagedIterable<PolicyDefinition> policyDefinitions = azureResourceManager.policyDefinitions().list();
        policyDefinitions.forEach((policyDefinition -> {
            PolicyDefinitionDto dto = PolicyDefinitionDto.builder()
                    .id(policyDefinition.id())
                    .azureId(policyDefinition.innerModel().id())
                    .policyType(policyDefinition.policyType().toString())
                    .policyRule(policyDefinition.policyRule())
                    .description(policyDefinition.description())
                    .displayName(policyDefinition.displayName())
                    .mode(policyDefinition.mode())
                    .build();
            policies.add(dto);
        }));
        return policies;
    }


    public List<PolicyAssignmentDTO> listPolicyAssignments() {
        List<PolicyAssignmentDTO> policies = new ArrayList<>();
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        PagedIterable<PolicyAssignment> policyDefinitions = azureResourceManager.policyAssignments().list();
        policyDefinitions.forEach((policyAssignment -> {
            PolicyAssignmentDTO dto = PolicyAssignmentDTO.builder()
                    .id(policyAssignment.id())
                    .azureId(policyAssignment.innerModel().id())
                    .policyDefinitionId(policyAssignment.policyDefinitionId())
                    .displayName(policyAssignment.displayName())
                    .scope(policyAssignment.scope())
                    .excludedScopes(policyAssignment.excludedScopes())
                    .type(policyAssignment.type())
                    .enforcementMode(policyAssignment.enforcementMode().toString())
                    .build();
            policies.add(dto);
        }));
        return policies;
    }


    private boolean isCustomRole(String roleType) {
        return !Objects.equals(roleType, "BuiltInRole");
    }
}

