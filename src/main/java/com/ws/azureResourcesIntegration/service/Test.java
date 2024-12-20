package com.ws.azureResourcesIntegration.service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Test {
    public static void main(String[] args) {
        // Example scopes
        String scope1 = "/subscriptions/{subscription-id}"; // Expected: SUBSCRIPTION
        String scope2 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}"; // Expected: RESOURCE-GROUP
        String scope3 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Compute/virtualMachines/{vm-name}"; // Expected: VM
        String scope31 = "/subscriptions/15b85f1d-1983-469c-a593-46fe8fc514f7/resourceGroups/TESTING-WORKLOAD_GROUP/providers/Microsoft.Compute/virtualMachines/testing-workload";
        String scope4 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Storage/storageAccounts/{storage-account-name}"; // Expected: STORAGE-ACCOUNT
        String scope5 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Sql/servers/{server-name}"; // Expected: SERVER
        String scope6 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Sql/databases/{database-name}"; // Expected: DATABASE
        String scope7 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Network/networkSecurityGroups/{nsg-name}"; // Expected: NETWORK-SECURITY-GROUP
        String scope8 = "/subscriptions/{subscription-id}/resourceGroups/{resource-group-name}/providers/Microsoft.Network/virtualNetworks/{vnet-name}"; // Expected: VIRTUAL-NETWORK
        String scope9 = "/providers/Microsoft.Management/managementGroups/{management-group-name}"; // Expected: MANAGEMENT-GROUP

        // Test the method with all examples
        System.out.println(determineScopeType(scope1)); // Expected: SUBSCRIPTION
        System.out.println(determineScopeType(scope2)); // Expected: RESOURCE-GROUP
        System.out.println(determineScopeType(scope3)); // Expected: VM
        System.out.println(determineScopeType(scope4)); // Expected: STORAGE-ACCOUNT
        System.out.println(determineScopeType(scope5)); // Expected: SERVER
        System.out.println(determineScopeType(scope6)); // Expected: DATABASE
        System.out.println(determineScopeType(scope7)); // Expected: NETWORK-SECURITY-GROUP
        System.out.println(determineScopeType(scope8)); // Expected: VIRTUAL-NETWORK
        System.out.println(determineScopeType(scope31)); // Expected: VIRTUAL-NETWORK
        System.out.println(determineScopeType(scope9)); // Expected: MANAGEMENT-GROUP
    }

    public static String determineScopeType(String scope) {
        // Split the scope path by "/"
        String[] pathSegments = scope.split("/");

        // Check for subscription level
        if (pathSegments.length >= 2 && pathSegments[1].equals("subscriptions")) {

            // Check if it's just a subscription level
            if (pathSegments.length == 3) {
                return "SUBSCRIPTION";  // Only subscription, no further resources
            }

            // Check if it's a resource group level
            if (pathSegments.length >= 4 && pathSegments[3].equals("resourceGroups")) {
                if (pathSegments.length == 5) {
                    return "RESOURCE-GROUP";  // Exact path for resource group level
                }

                // Find the position of "providers"
                int providersIndex = -1;
                for (int i = 4; i < pathSegments.length; i++) {
                    if (pathSegments[i].equals("providers")) {
                        providersIndex = i;
                        break;
                    }
                }

                // If "providers" is present, check for specific resources
                if (providersIndex != -1 && pathSegments.length > providersIndex + 3) {
                    // Check for VM
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Compute") && pathSegments[providersIndex + 2].equals("virtualMachines")) {
                        return "VM";
                    }

                    // Check for Storage Account
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Storage") && pathSegments[providersIndex + 2].equals("storageAccounts")) {
                        return "STORAGE-ACCOUNT";
                    }

                    // Check for Server
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Sql") && pathSegments[providersIndex + 2].equals("servers")) {
                        return "SERVER";
                    }

                    // Check for Database (e.g., Azure SQL Database)
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Sql") && pathSegments[providersIndex + 2].equals("databases")) {
                        return "DATABASE";
                    }

                    // Check for Network Security Group
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Network") && pathSegments[providersIndex + 2].equals("networkSecurityGroups")) {
                        return "NETWORK-SECURITY-GROUP";
                    }

                    // Check for Virtual Network
                    if (pathSegments[providersIndex + 1].equals("Microsoft.Network") && pathSegments[providersIndex + 2].equals("virtualNetworks")) {
                        return "VIRTUAL-NETWORK";
                    }
                }
            }
        }

        // Check for management group
        if (pathSegments.length >= 2 && pathSegments[1].equals("providers")
                && pathSegments.length >= 4 && pathSegments[3].equals("managementGroups")) {
            return "MANAGEMENT-GROUP";
        }

        // If no valid match, return UNKNOWN
        return "UNKNOWN";
    }
}


