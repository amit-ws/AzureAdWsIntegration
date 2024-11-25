package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.appservice.models.FunctionAppBasic;
import com.azure.resourcemanager.appservice.models.WebAppBasic;
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
     */
    public void listVMs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<VirtualMachine> vms = azureResourceManager.virtualMachines().list();
        for (VirtualMachine vm : vms) {
            log.info("id: {}", vm.id());
            log.info("name: {}", vm.name());
            log.info("computer name: {}", vm.computerName());
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

}
