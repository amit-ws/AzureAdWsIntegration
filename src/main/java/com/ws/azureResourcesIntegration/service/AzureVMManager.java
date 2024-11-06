package com.ws.azureResourcesIntegration.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.compute.models.VirtualMachine;
import com.azure.resourcemanager.resources.fluentcore.arm.models.ResourceGroup;

public class AzureVMManager {
    private static final String SUBSCRIPTION_ID = "your-subscription-id";
    private static final String RESOURCE_GROUP_NAME = "your-resource-group-name";

    public static AzureResourceManager authenticate() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId("your-client-id")
                .tenantId("your-tenant-id")
                .clientSecret("your-client-secret")
                .build();

        return AzureResourceManager
                .configure()
                .authenticate(credential, SUBSCRIPTION_ID)
                .withDefaultSubscription();
    }

    // List all VMs in a resource group
    public static void listVMs() {
        AzureResourceManager azure = authenticate();
        azure.virtualMachines().listByResourceGroup(RESOURCE_GROUP_NAME)
                .forEach(vm -> System.out.println("VM name: " + vm.name()));
    }

    // Start a VM
    public static void startVM(String vmName) {
        AzureResourceManager azure = authenticate();
        VirtualMachine vm = azure.virtualMachines().getByResourceGroup(RESOURCE_GROUP_NAME, vmName);
        vm.start();
        System.out.println("VM started: " + vmName);
    }

    // Stop a VM
    public static void stopVM(String vmName) {
        AzureResourceManager azure = authenticate();
        VirtualMachine vm = azure.virtualMachines().getByResourceGroup(RESOURCE_GROUP_NAME, vmName);
        vm.powerOff();
        System.out.println("VM stopped: " + vmName);
    }
}
