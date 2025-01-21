package com.ws.azureAdIntegration.service;

import com.microsoft.graph.models.DirectoryObject;
import com.microsoft.graph.models.Organization;
import com.microsoft.graph.models.User;
import com.microsoft.graph.requests.*;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureADInitializerService {
    GraphServiceClient<Request> graphClient;
    final AzureAuthUtil azureAuthUtil;


    @Autowired
    public AzureADInitializerService(AzureAuthUtil azureAuthUtil) {
        this.azureAuthUtil = azureAuthUtil;
    }

    public void initializeGraphClient(AzureUserCredentialDTO azureUserCredentialDTO, GraphServiceClient<Request> client) {
        this.graphClient = Optional.ofNullable(client)
                .orElseGet(() -> {
                    log.info("Validating user's Azure-AD credentials..");
                    return azureAuthUtil.validateAzureCredentials(azureUserCredentialDTO);
                });
    }

    public Organization getOrganizationUsingTenantId(@NotNull(message = "Tenant id not provided to fetch Azure-Tenant") String tenantId) {
        return this.graphClient.organization(tenantId)
                .buildRequest()
                .get();
    }

    public ApplicationCollectionPage getApplicationCollection() {
        return this.graphClient.applications()
                .buildRequest()
                .get();
    }

    public UserCollectionPage getUsersCollection() {
        return this.graphClient.users()
                .buildRequest()
                .get();
    }

    public GroupCollectionPage getGroupCollection() {
        return this.graphClient.groups()
                .buildRequest()
                .get();
    }

    public DeviceCollectionPage getDeviceCollection() {
        return this.graphClient.devices()
                .buildRequest()
                .get();
    }

    public List<DirectoryObject> getGroupsOfUser(String userId) {
        return this.graphClient.users(userId)
                .memberOf()
                .buildRequest()
                .get()
                .getCurrentPage()
                .stream()
                .filter(member -> "#microsoft.graph.group".equals(member.oDataType))
                .toList();
    }

    public List<DirectoryObject> getDevicesOfUser(String userId) {
        return this.graphClient.users(userId)
                .registeredDevices()
                .buildRequest()
                .get()
                .getCurrentPage()
                .stream()
                .filter(member -> "#microsoft.graph.group".equals(member.oDataType))
                .collect(Collectors.toList());
    }


    public User findUserByUserId(@NotNull(message = "Azure Id is required") String azureId) {
        try {
            return this.graphClient.users(azureId.trim()).buildRequest().get();
        } catch (Exception exp) {
            throw new RuntimeException(exp.getMessage());
        }
    }
}
