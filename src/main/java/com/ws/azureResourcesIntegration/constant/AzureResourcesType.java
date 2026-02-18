package com.ws.azureResourcesIntegration.constant;

import lombok.Getter;

@Getter
public enum AzureResourcesType {
    SUBSCRIPTION("Subscription"),
    RESOURCE_GROUP("Resource Group"),
    VIRTUAL_MACHINE("Virtual Machine"),
    STORAGE_ACCOUNT("Storage Account"),
    SERVER("Server"),
    DATABASE("Database"),
    NETWORK_SECURITY_GROUP("Network Security Group"),
    VIRTUAL_NETWORK("Virtual Network"),
    MANAGEMENT_GROUP("Management Group"),
    AZURE_KUBERNETES("Azure Kubernetes"),
    NETWORK_INTERFACE("Network Interface"),
    UNKNOWN("Unknown");

    final String displayName;

    AzureResourcesType(String displayName) {
        this.displayName = displayName;
    }
}
