package com.ws.azureResourcesIntegration.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.appservice.models.WebAppBasic;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.compute.models.VirtualMachineScaleSet;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
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

    @Value("${spring.cloud.azure.active-directory.resource-group-name}")
    String resourceGroupName;


    @Autowired
    public AzureResourcesService(AzureResourceAuthFactory azureResourceAuthFactory) {
        this.azureResourceAuthFactory = azureResourceAuthFactory;
    }

    private AzureResourceManager getAzureResourceManager() {
        return azureResourceAuthFactory.createResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    /**
     * List all VMs in a resource group
     */
    public void listVMs() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        azureResourceManager.virtualMachines().listByResourceGroup(resourceGroupName)
                .forEach(vm -> log.info("VM name: {} ", vm.name()));
    }

    /**
     * Start a VM
     */
    public void startVM(String vmName) {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        VirtualMachine vm = azureResourceManager.virtualMachines().getByResourceGroup(resourceGroupName, vmName);
        vm.start();
        log.info("VM started: {}", vmName);
    }

    /**
     * Stop a VM
     */
    public void stopVM(String vmName) {
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
        List<SqlServer> sqlServers = (List<SqlServer>) azureResourceManager.sqlServers().list();
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
            System.out.println("App Name: " + app.name());
            System.out.println("Region: " + app.regionName());
            System.out.println("Resource Group: " + app.resourceGroupName());
            System.out.println("App Service Plan: " + app.appServicePlanId());
        }
    }


    /**
     * List Storage Accounts:
     */
    public void listStorageAccounts() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<StorageAccount> storageAccounts = azureResourceManager.storageAccounts().list();
        for (StorageAccount storageAccount : storageAccounts) {
            System.out.println("Storage Account Name: " + storageAccount.name());
            System.out.println("Resource Group: " + storageAccount.resourceGroupName());
            System.out.println("Region: " + storageAccount.regionName());
            System.out.println("SKU type: " + storageAccount.skuType());
        }
    }


    /**
     * List all Load Balancers
     */
    public void listAllLoadBalancers() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<LoadBalancer> loadBalancers = azureResourceManager.networks().manager().loadBalancers().list();
        for (LoadBalancer lb : loadBalancers) {
            System.out.println("Load Balancer Name: " + lb.name());
            System.out.println("Region: " + lb.regionName());
            System.out.println("Resource Group: " + lb.resourceGroupName());
        }
    }

    /**
     * List all AKS clusters
     */
    public void listAKSClusters() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<KubernetesCluster> aksClusters = azureResourceManager.kubernetesClusters().list();
        for (KubernetesCluster cluster : aksClusters) {
            System.out.println("Cluster Name: " + cluster.name());
            System.out.println("Region: " + cluster.regionName());
            System.out.println("Resource Group: " + cluster.resourceGroupName());
            System.out.println("Kubernetes Version: " + cluster.version());
        }
    }


    /**
     * List Network Security Groups (NSGs)
     */
    public void listNetworkSecurityGroups() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<NetworkSecurityGroup> nsgs = azureResourceManager.networks().manager().networkSecurityGroups().list();
        for (NetworkSecurityGroup nsg : nsgs) {
            System.out.println("NSG Name: " + nsg.name());
            System.out.println("Region: " + nsg.regionName());
            System.out.println("Resource Group: " + nsg.resourceGroupName());
            System.out.println("Security Rules Count: " + nsg.securityRules().size());
        }
    }


    /**
     * List Application Gateways
     */
    public void listApplicationGateways() {
        AzureResourceManager azureResourceManager = getAzureResourceManager();
        PagedIterable<ApplicationGateway> applicationGateways = azureResourceManager.networks().manager().applicationGateways().list();
        for (ApplicationGateway appGw : applicationGateways) {
            System.out.println("Application Gateway Name: " + appGw.name());
            System.out.println("Resource Group: " + appGw.resourceGroupName());
            System.out.println("Region: " + appGw.regionName());

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
            System.out.println("VM Scale Set Name: " + vmScaleSet.name());
            System.out.println("Resource Group: " + vmScaleSet.resourceGroupName());
            System.out.println("Region: " + vmScaleSet.regionName());
            System.out.println("Number of VMs: " + vmScaleSet.virtualMachines().list().stream().toList().size());
        }

    }


}
