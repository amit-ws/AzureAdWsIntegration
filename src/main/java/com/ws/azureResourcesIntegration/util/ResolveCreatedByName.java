package com.ws.azureResourcesIntegration.util;

import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.microsoft.graph.core.ClientException;
import com.microsoft.graph.requests.GraphServiceClient;

import java.util.Objects;

public class ResolveCreatedByName {

    public static String resolveCreatedByDisplayNameBasedOnPrincipleType(PrincipalType principalType, String createdBy, GraphServiceClient serviceClient) {
        return fetchDisplayNameForPrincipal(principalType, createdBy, serviceClient);
    }

    private static String fetchDisplayNameForPrincipal(PrincipalType principalType, String createdBy, GraphServiceClient serviceClient) {
        try {
            if (principalType.equals(PrincipalType.USER)) {
                return getDisplayNameFromUser(createdBy, serviceClient);
            } else if (principalType.equals(PrincipalType.GROUP)) {
                return getDisplayNameFromGroup(createdBy, serviceClient);
            } else if (principalType.equals(PrincipalType.SERVICE_PRINCIPAL)) {
                return getDisplayNameFromServicePrincipal(createdBy, serviceClient);
            } else if (principalType.equals(PrincipalType.DEVICE)) {
                return getDisplayNameFromDevice(createdBy, serviceClient);
            }
            return null;
        } catch (ClientException e) {
            return null;
        }
    }

    private static String getDisplayNameFromUser(String createdBy, GraphServiceClient serviceClient) {
        return Objects.requireNonNull(serviceClient.users(createdBy).buildRequest().get()).displayName;
    }

    private static String getDisplayNameFromGroup(String createdBy, GraphServiceClient serviceClient) {
        return Objects.requireNonNull(serviceClient.groups(createdBy).buildRequest().get()).displayName;
    }

    private static String getDisplayNameFromServicePrincipal(String createdBy, GraphServiceClient serviceClient) {
        return Objects.requireNonNull(serviceClient.servicePrincipals(createdBy).buildRequest().get()).displayName;
    }

    private static String getDisplayNameFromDevice(String createdBy, GraphServiceClient serviceClient) {
        return Objects.requireNonNull(serviceClient.devices(createdBy).buildRequest().get()).displayName;
    }

    /*  ORIGINAL

        private String resolveCreatedByDisplayNameBasedOnPrincipleType(PrincipalType principalType, String createdBy) {
        GraphServiceClient serviceClient = getGraphServiceClient();
        if (principalType.equals(PrincipalType.USER)) {
            try {
                return Objects.requireNonNull(serviceClient.users(createdBy).buildRequest().get()).displayName;
            } catch (ClientException e) {
                return null;
            }
        } else if (principalType.equals(PrincipalType.GROUP)) {
            try {
                return Objects.requireNonNull(serviceClient.groups(createdBy).buildRequest().get()).displayName;
            } catch (ClientException e) {
                return null;
            }
        } else if (principalType.equals(PrincipalType.SERVICE_PRINCIPAL)) {
            try {
                return Objects.requireNonNull(serviceClient.servicePrincipals(createdBy).buildRequest().get()).displayName;
            } catch (ClientException e) {
                return null;
            }
        } else if (principalType.equals(PrincipalType.DEVICE)) {
            try {
                return Objects.requireNonNull(serviceClient.devices(createdBy).buildRequest().get()).displayName;
            } catch (ClientException e) {
                return null;
            }
        } else {
            return null;
        }
    }

     */

}
