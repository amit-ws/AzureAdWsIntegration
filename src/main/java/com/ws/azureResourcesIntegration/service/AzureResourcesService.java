package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.appservice.models.FunctionAppBasic;
import com.azure.resourcemanager.appservice.models.WebAppBasic;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.azure.resourcemanager.compute.models.*;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.azure.resourcemanager.keyvault.models.Vault;
import com.azure.resourcemanager.network.models.*;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.sql.models.SqlDatabase;
import com.azure.resourcemanager.sql.models.SqlServer;
import com.azure.resourcemanager.storage.models.StorageAccount;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;


@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourcesService {
    final AzureResourceAuthFactory azureResourceAuthFactory;
    @Value("${spring.cloud.azure.active-directory.client-id}")
    String clientId;

    @Value("${spring.cloud.azure.active-directory.client-secret}")
    String clientSecret;

    @Value("${spring.cloud.azure.active-directory.tenant-id}")
    String tenantId;

    @Value("${spring.cloud.azure.active-directory.subscription-id}")
    String subscriptionId;


    @Autowired
    public AzureResourcesService(AzureResourceAuthFactory azureResourceAuthFactory) {
        this.azureResourceAuthFactory = azureResourceAuthFactory;
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureResourceAuthFactory.createResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    /**
     * List all VMs
     * Equivalent to AWS EC2
     */
    public void listVMs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachine> vms = azureResourceManager.virtualMachines().list();
        for (VirtualMachine vm : vms) {
            log.info("VM ID: {}", vm.id());
            log.info("VM Name: {}", vm.name());
            log.info("computer name: {}", vm.computerName());
            log.info("VM State: {}", vm.powerState().toString()); // e.g., "Running" or "Stopped"
            log.info("VM Size: {}", vm.size());
            log.info("VM OS: {}", vm.osType().toString());
            log.info("Public IP: {}", vm.getPrimaryPublicIPAddressId());
            log.info("Resource Group: {}", vm.resourceGroupName());

            // Listing private and public address ids
            NetworkInterface networkInterface = vm.getPrimaryNetworkInterface();
            if (networkInterface != null) {
                // Get the primary IP configuration from the network interface
                NicIpConfiguration ipConfig = networkInterface.ipConfigurations().values().iterator().next();
                // Private IP address
                String privateIp = ipConfig.privateIpAddress();
                log.info("Private IP: {}", privateIp);

                // Public IP address (if exists)
                String publicIp = ipConfig.getPublicIpAddress() != null
                        ? ipConfig.getPublicIpAddress().ipAddress()
                        : "No Public IP";
                log.info("Public IP: {}", publicIp);
            }
        }
    }


    /**
     * List all VMs in a resource group
     */
    public void listVMsByResource(String resourceGroupName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        azureResourceManager.virtualMachines().listByResourceGroup(resourceGroupName)
                .forEach(vm -> log.info("VM name: {} ", vm.name()));
    }

    /**
     * Start a VM
     */
    public void startVM(String resourceGroupName, String vmName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        VirtualMachine vm = azureResourceManager.virtualMachines().getByResourceGroup(resourceGroupName, vmName);
        vm.start();
        log.info("VM started: {}", vmName);
    }

    /**
     * Stop a VM
     */
    public void stopVM(String resourceGroupName, String vmName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        VirtualMachine vm = azureResourceManager.virtualMachines().getByResourceGroup(resourceGroupName, vmName);
        vm.powerOff();
        System.out.println("VM stopped: " + vmName);
    }


    /**
     * Get all Servers with their respective DBs
     */
    public void getServersAndDBS() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<SqlServer> sqlServers = azureResourceManager.sqlServers().list();
        for (SqlServer sqlServer : sqlServers) {
            log.info("Server name: {}", sqlServer.name());
            log.info("Server Region: {}", sqlServer.regionName());

            List<SqlDatabase> sqlDatabases = sqlServer.databases().list();
            for (SqlDatabase sqlDatabase : sqlDatabases) {
                log.info("SQL DB id: {}", sqlDatabase.id());
                log.info("SQL DB name: {}", sqlDatabase.name());
            }
        }
    }


    /**
     * List all Virtual Networks
     */
    public void listVirtualNetworks() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<Network> virtualNetworks = azureResourceManager.networks().list();
        for (Network network : virtualNetworks) {
            log.info("name: {}", network.name());
            log.info("address space: {}", network.addressSpaces());
            log.info("resource group name: {}", network.resourceGroupName());
        }
    }

    /**
     * List all Resource Groups
     */
    public void listResourceGroups() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<ResourceGroup> resourceGroups = azureResourceManager.resourceGroups().list();
        for (ResourceGroup resourceGroup : resourceGroups) {
            log.info("Id: {}", resourceGroup.id());
            log.info("Name: {}", resourceGroup.name());
            log.info("Region name: {}", resourceGroup.regionName());
        }
    }

    /**
     * List App Services
     */
    public void listAppServices() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<WebAppBasic> webApps = azureResourceManager.webApps().list();
        for (WebAppBasic app : webApps) {
            log.info("App Name: " + app.name());
            log.info("Region: " + app.regionName());
            log.info("Resource Group: " + app.resourceGroupName());
            log.info("App Service Plan: " + app.appServicePlanId());
        }
    }


    /**
     * List Storage Accounts:
     */
    public void listStorageAccounts() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<StorageAccount> storageAccounts = azureResourceManager.storageAccounts().list();
        for (StorageAccount storageAccount : storageAccounts) {
            log.info("Storage Account Name: " + storageAccount.name());
            log.info("Resource Group: " + storageAccount.resourceGroupName());
            log.info("Region: " + storageAccount.regionName());
            log.info("SKU type: " + storageAccount.skuType());
        }
    }


    /**
     * List all Load Balancers
     */
    public void listAllLoadBalancers() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<LoadBalancer> loadBalancers = azureResourceManager.networks().manager().loadBalancers().list();
        for (LoadBalancer lb : loadBalancers) {
            log.info("Load Balancer Name: " + lb.name());
            log.info("Region: " + lb.regionName());
            log.info("Resource Group: " + lb.resourceGroupName());
        }
    }

    /**
     * List all AKS clusters
     */
    public void listAKSClusters() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<KubernetesCluster> aksClusters = azureResourceManager.kubernetesClusters().list();
        for (KubernetesCluster cluster : aksClusters) {
            log.info("Cluster Name: " + cluster.name());
            log.info("Region: " + cluster.regionName());
            log.info("Resource Group: " + cluster.resourceGroupName());
            log.info("Kubernetes Version: " + cluster.version());
        }
    }


    /**
     * List Network Security Groups (NSGs)
     */
    public void listNetworkSecurityGroups() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<NetworkSecurityGroup> nsgs = azureResourceManager.networks().manager().networkSecurityGroups().list();
        for (NetworkSecurityGroup nsg : nsgs) {
            log.info("NSG Name: " + nsg.name());
            log.info("Region: " + nsg.regionName());
            log.info("Resource Group: " + nsg.resourceGroupName());
            log.info("Security Rules Count: " + nsg.securityRules().size());
        }
    }


    /**
     * List Application Gateways
     */
    public void listApplicationGateways() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<ApplicationGateway> applicationGateways = azureResourceManager.networks().manager().applicationGateways().list();
        for (ApplicationGateway appGw : applicationGateways) {
            log.info("Application Gateway Name: " + appGw.name());
            log.info("Resource Group: " + appGw.resourceGroupName());
            log.info("Region: " + appGw.regionName());

            // Access frontend configurations
            Map<String, ApplicationGatewayFrontend> frontends = appGw.frontends();
            for (Map.Entry<String, ApplicationGatewayFrontend> frontendEntry : frontends.entrySet()) {
                ApplicationGatewayFrontend frontend = frontendEntry.getValue();
                log.info("name: {}", frontend.name());
                log.info("Frontend IP Private IP: {}", frontend.privateIpAddress());
                log.info("Frontend IP Public IP: {}", frontend.publicIpAddressId());
            }
        }
    }


    /**
     * List VM Scale Sets
     */
    public void listVMScaleSets() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachineScaleSet> vmScaleSets = azureResourceManager.virtualMachineScaleSets().list();
        for (VirtualMachineScaleSet vmScaleSet : vmScaleSets) {
            log.info("VM Scale Set Name: " + vmScaleSet.name());
            log.info("Resource Group: " + vmScaleSet.resourceGroupName());
            log.info("Region: " + vmScaleSet.regionName());
            log.info("Number of VMs: " + vmScaleSet.virtualMachines().list().stream().toList().size());
        }
    }

    /**
     * List Azure Functions
     */
    public void listAzureFunctions() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<FunctionAppBasic> functionApps = azureResourceManager.functionApps().list();
        for (FunctionAppBasic functionApp : functionApps) {
            log.info("Function App Name: " + functionApp.name());
            log.info("Resource Group: " + functionApp.resourceGroupName());
            log.info("Region: " + functionApp.regionName());
            log.info("Default Hostname: " + functionApp.defaultHostname());
        }
    }

    /**
     * List Key Vaults
     */
    public void listKeyVaults(String resourceGroupName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<Vault> keyVaults = azureResourceManager.vaults().listByResourceGroup(resourceGroupName);
        for (Vault vault : keyVaults) {
            log.info("Key Vault Name: " + vault.name());
            log.info("Resource Group: " + vault.resourceGroupName());
            log.info("Region: " + vault.regionName());
            log.info("SKU: " + vault.sku().name());
        }
    }


    /**
     * List Availability Sets
     */
    public void listAvailabilitySets() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<AvailabilitySet> availabilitySets = azureResourceManager.availabilitySets().list();
        for (AvailabilitySet availabilitySet : availabilitySets) {
            log.info("Availability Set Name: " + availabilitySet.name());
            log.info("Resource Group: " + availabilitySet.resourceGroupName());
            log.info("Region: " + availabilitySet.regionName());
            log.info("Fault Domain count: " + availabilitySet.faultDomainCount());
            log.info("Update Domain count: " + availabilitySet.updateDomainCount());
        }
    }

    /**
     * List Availability Sets by resource-group=name
     */
    public void listAvailabilitySets(String resourceGroupName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<AvailabilitySet> availabilitySets = azureResourceManager.availabilitySets().listByResourceGroup(resourceGroupName);
        for (AvailabilitySet availabilitySet : availabilitySets) {
            log.info("Availability Set Name: " + availabilitySet.name());
            log.info("Resource Group: " + availabilitySet.resourceGroupName());
            log.info("Region: " + availabilitySet.regionName());
            log.info("Fault Domain count: " + availabilitySet.faultDomainCount());
            log.info("Update Domain count: " + availabilitySet.updateDomainCount());
        }
    }


    /**
     * List Disks
     */
    public void getDisks() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<Disk> disks = azureResourceManager.disks().list();
        for (Disk disk : disks) {
            log.info("name: {}", disk.name());
            log.info("id: {}", disk.id());
            log.info("size in byte: {}", disk.sizeInByte());
        }
    }


    /**
     * List VirtualNetworkGateway
     */
    public void getVirtualNetworkGateway() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualNetworkGateway> vngs = azureResourceManager.virtualNetworkGateways().list();
        for (VirtualNetworkGateway vng : vngs) {
            log.info("name: {}", vng.name());
            log.info("type: {}", vng.vpnType());
            log.info("gateway type: {}", vng.gatewayType());
        }
    }


    /**
     * List RBAC (roles)
     * Equivalent to Aws_Iam_Policies
     */
    public void listRBACRoles() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleDefinition> roles = azureResourceManager.accessManagement().roleDefinitions().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        for (RoleDefinition role : roles) {
            log.info("Role ID: {}", role.id());
            log.info("Role Name: {}", role.roleName());
            log.info("Description: {}", role.description());
            log.info("IS ROLE CUSTOM? : {}", isCustomRole(role.id()));
            log.info("Custom Role: {}", isCustomRole(role.id()) ? "Yes" : "No");
        }
    }

    private boolean isCustomRole(String roleId) {
        // In Azure, custom roles have IDs that typically don't follow the pattern of built-in roles.
        // Built-in role definition IDs typically start with a prefix like below:
        String builtInPrefix = "/providers/Microsoft.Authorization/roleDefinitions/";
        return !roleId.startsWith(builtInPrefix); // or check for .contains() instead of startsWith()
    }


    /**
     * List Azure RBAC assignments
     * Equivalent to AWS_Attached_Policies
     * Stores mapping of policies (roles) <--> with specific users/groups
     */
    public void listRBACRolesAssignment() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        for (RoleAssignment assignment : assignments) {
            log.info("Role Assignment ID: {}", assignment.id());
            log.info("RoleDefinition ID: {}", assignment.roleDefinitionId());
            log.info("Name: {}", assignment.name());
            log.info("Description: {}", assignment.description());
            log.info("Assignee: {}", assignment.principalId());
            RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(assignment.roleDefinitionId());
            log.info("Assigned Role: {}", roleDefinition.name());
            log.info("Custom Role: {}", isCustomRole(roleDefinition.id()) ? "Yes" : "No");
        }
    }


    /**
     * DEFINITIONS
     * AWS_ROLES => the set of permissions which can be assigned to users or groups
     * AWS_POLICIES => What particular actions does each of these permissions (roles) can do
     *
     * So in the context of Azure: We have RoleDefinition corresponding to AWS_ROLE and,
     * we have roleDefinition.permissions() talking about the set of actions the specific role can play
     *
     * Note: The specific role (RoleDefinition) can be of two types: 1. Attached (in-built) and 2. Inline (custom)
     *
     * Now coming to RoleAssignment, it means the mapping of Azure Roles against specific users groups etc
     */

    /**
     * Equivalent to aws_roles
     * CREATE TABLE public.azure_roles (
     * role_id varchar(255) NOT NULL,          -- Role definition ID (similar to AWS Role ARN)
     * created_date varchar(255) NULL,         -- Creation date of the role definition
     * is_attachable varchar(255) NULL,        -- Indicates if the role can be attached to a resource or user
     * role_name varchar(255) NULL,            -- Name of the role (e.g., 'Contributor', 'Reader')
     * role_definition_id varchar(255) NULL,   -- Role definition ID
     * tenant_name varchar(255) NULL,          -- Tenant associated with the role definition
     * is_custom_role bool DEFAULT false,      -- Whether the role is custom (true/false)
     * );
     */
    public void listAzureRoleAssignments() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        for (RoleAssignment assignment : assignments) {

        }

    }


    /**
     * List details of roles mapped with users/groups etc
     * types: Attached (in azure = Predefined) and Inline (in azure = Custom)
     * Equivalent to aws_role_attached_permissions and aws_role_in_line_permissions
     * Structure:
     * CREATE TABLE public.azure_custom_role_in_line_permissions (
     * azure_role_id varchar(255) NOT NULL,       -- Role Definition ID (Custom Role in Azure)
     * policy_name varchar(255) NULL,              -- Name of the Custom Role (like inline policy name)
     * principal_id varchar(255) NULL,             -- The ID of the user/group/service principal assigned to the role
     * tenant_name varchar(255) NULL,              -- Tenant or Subscription Name
     * );
     */
    public void listAzurePermissions() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<RoleAssignment> assignments = azureResourceManager.accessManagement().roleAssignments().listByScope(String.format("/subscriptions/%s", azureResourceManager.subscriptionId()));
        for (RoleAssignment assignment : assignments) {
            log.info("azure_role_id ID: {}", assignment.roleDefinitionId());
            log.info("principal_id: {}", assignment.principalId());
            RoleDefinition roleDefinition = azureResourceManager.accessManagement().roleDefinitions().getById(assignment.roleDefinitionId());
            log.info("policy_name: {}", roleDefinition.name());
            // checking whether its attached or inline
            log.info(isCustomRole(roleDefinition.id()) ? "inline" : "attached");
        }
    }


    /**
     * List NSG specific for VMs
     * Purpose: Control traffic to and from VMs or network interfaces
     * Equivalent to aws_ec2_security_groups
     */
    public void listAzureNSGs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachine> vms = azureResourceManager.virtualMachines().list();

        for (VirtualMachine vm : vms) {
            log.info("VM ID: {}", vm.id());
            // Get the network interface associated with the VM
            NetworkInterface networkInterface = vm.getPrimaryNetworkInterface();

            if (networkInterface != null) {
                // Retrieve the NSG associated with the NIC
                NetworkSecurityGroup nsg = networkInterface.getNetworkSecurityGroup();
                if (nsg != null) {
                    log.info("Associated NSG Name: {}", nsg.name());
                    log.info("NSG ID: {}", nsg.id());
                } else {
                    log.info("No NSG associated with this VM.");
                }
            }
        }
    }
}