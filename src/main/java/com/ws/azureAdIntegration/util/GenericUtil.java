package com.ws.azureAdIntegration.util;

import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import org.apache.commons.lang3.tuple.Triple;

import java.util.*;
import java.util.function.Supplier;

public class GenericUtil {

    public static Map<String, String> extractServerAndTokenFromKubeConfigYAML(String config) {

        final String serverPrefix = "server: ";
        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
        int serverEnd = config.indexOf("\n", serverStart);

        final String tokenPrefix = "token: ";
        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
        int tokenEnd = config.indexOf("\n", tokenStart);

        Map<String, String> value = new HashMap<>();
        value.put("server", config.substring(serverStart, serverEnd).trim());
        value.put("token", config.substring(tokenStart, tokenEnd).trim());

        return value;
    }


    public static Triple<String, String, String> extractServerTokenAndCaCertBase64FromKubeConfigYAML(String config) {
        String[] result = new String[3];

        String serverPrefix = "server: ";
        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
        int serverEnd = config.indexOf("\n", serverStart);
        result[0] = config.substring(serverStart, serverEnd).trim();

        String tokenPrefix = "token: ";
        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
        int tokenEnd = config.indexOf("\n", tokenStart);
        result[1] = config.substring(tokenStart, tokenEnd).trim();

        String caCertPrefix = "certificate-authority-data: ";
        int caCertStart = config.indexOf(caCertPrefix) + caCertPrefix.length();
        int caCertEnd = config.indexOf("\n", caCertStart);
        result[2] = config.substring(caCertStart, caCertEnd).trim();

        return Triple.of(result[0], result[1], result[2]);
    }

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

    public static <T> List<T> getOrEmptyList(Supplier<List<T>> supplier) {
        try {
            return supplier != null ? supplier.get() : Collections.emptyList();
        } catch (NullPointerException e) {
            return Collections.emptyList();
        }
    }

    public static List<String> splitStringConvertToList(String input) {
        if (input != null && !input.equals("null")) {
            return new ArrayList<>(List.of(input.split(",")));
        } else {
            return new ArrayList<>();
        }
    }

    public static String extractLastValue(String input) {
        if (input == null) {
            return null;
        }
        String[] parts = input.split("/");
        if (parts.length == 0) {
            return null;
        }
        return parts[parts.length - 1];
    }

    public static String extractLastValueOld(String input) {
        if (input == null) {
            return null;
        }
        return Arrays.stream(input.split("/"))
                .filter(part -> !part.isEmpty())
                .reduce((first, second) -> second)
                .orElse(null);
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
                        return AzureResourcesType.VIRTUAL_MACHINE.name();
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

                    // Check for Azure Kubernetes Cluster (AKS)
                    if (pathSegments[providersIndex + 1].equalsIgnoreCase("Microsoft.ContainerService") && pathSegments[providersIndex + 2].equalsIgnoreCase("managedClusters")) {
                        return AzureResourcesType.AZURE_KUBERNETES.name();
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