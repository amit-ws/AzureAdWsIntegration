package com.ws.azureAdIntegration.util;

import com.ws.azureResourcesIntegration.constant.AzureResourcesType;

import java.util.Optional;
import java.util.function.Supplier;

public class GenericUtil {
    public static void ensureNotNull(Object object, String message) {
        Optional.ofNullable(object)
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    public static <T> T getOrNull(Supplier<T> supplier) {
        try {
            return supplier != null ? supplier.get() : null;
        } catch (NullPointerException e) {
            return null;
        }
    }

    public static String determineScopeType(String scope) {
        String[] pathSegments = scope.split("/");

        // Check for subscription level
        if (pathSegments.length >= 2 && pathSegments[1].equalsIgnoreCase("subscriptions")) {
            if (pathSegments.length == 3) {
                return AzureResourcesType.SUBSCRIPTION.name();
            }

            // Check if it's a resource group level
            if (pathSegments.length >= 4 && pathSegments[3].equalsIgnoreCase("resourceGroups")) {
                if (pathSegments.length == 5) {
                    return AzureResourcesType.RESOURCE_GROUP.name();
                }

                // Find the position of "providers"
                int providersIndex = -1;
                for (int i = 4; i < pathSegments.length; i++) {
                    if (pathSegments[i].equalsIgnoreCase("providers")) {
                        providersIndex = i;
                        break;
                    }
                }

                // If "providers" is present, check for specific resources
                if (providersIndex != -1 && pathSegments.length > providersIndex + 3) {
                    // Check for VM
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Compute") && pathSegments[providersIndex + 2].equalsIgnoreCase("virtualMachines")) {
                        return AzureResourcesType.VM.name();
                    }

                    // Check for Storage Account
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Storage") && pathSegments[providersIndex + 2].equalsIgnoreCase("storageAccounts")) {
                        return AzureResourcesType.STORAGE_ACCOUNT.name();
                    }

                    // Check for Server
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Sql") && pathSegments[providersIndex + 2].equalsIgnoreCase("servers")) {
                        return AzureResourcesType.SERVER.name();
                    }

                    // Check for Database (e.g., Azure SQL Database)
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Sql") && pathSegments[providersIndex + 2].equalsIgnoreCase("databases")) {
                        return AzureResourcesType.DATABASE.name();
                    }

                    // Check for Network Security Group
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Network") && pathSegments[providersIndex + 2].equalsIgnoreCase("networkSecurityGroups")) {
                        return AzureResourcesType.NETWORK_SECURITY_GROUP.name();
                    }

                    // Check for Virtual Network
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.Network") && pathSegments[providersIndex + 2].equalsIgnoreCase("virtualNetworks")) {
                        return AzureResourcesType.VIRTUAL_NETWORK.name();
                    }
                }
            }
        }

        // Check for management group
        if (pathSegments.length >= 2 && pathSegments[1].equalsIgnoreCase("providers")
                && pathSegments.length >= 4 && pathSegments[3].equalsIgnoreCase("managementGroups")) {
            return AzureResourcesType.MANAGEMENT_GROUP.name();
        }

        // If no valid match, return UNKNOWN
        return AzureResourcesType.UNKNOWN.name();
    }

}