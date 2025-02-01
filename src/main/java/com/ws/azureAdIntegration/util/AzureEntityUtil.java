package com.ws.azureAdIntegration.util;

import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.microsoft.graph.models.*;
import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.entity.*;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.dto.AssignRoleRequest;
import com.ws.azureResourcesIntegration.dto.PublishResourceRequest;
import com.ws.azureResourcesIntegration.entities.AzureRoleAssignment;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import com.ws.azureResourcesIntegration.entities.PublishedResource;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.UUID;

@Slf4j
public final class AzureEntityUtil {
    private AzureEntityUtil() {
    }

    public static AzureTenant createAzureTenantFromGraphOrganization(Organization organization, AzureTenant azureTenant) {
        GenericUtil.ensureNotNull(organization, "Graph-Organization cannot be null");
        azureTenant.setAzureId(organization.id);
        azureTenant.setDisplayName(organization.displayName);
        azureTenant.setCountryLetterCode(organization.countryLetterCode);
        azureTenant.setAzureCreatedDateTime(organization.createdDateTime);
        azureTenant.setPostalCode(organization.postalCode);
        azureTenant.setPreferredLanguage(organization.preferredLanguage);
        azureTenant.setState(organization.state);
        azureTenant.setStreet(organization.street);
        azureTenant.setTenantType(organization.tenantType);
        azureTenant.setSyncedAt(new Date());
        azureTenant.setIsSSOEnabled(true);
        return azureTenant;
    }

    public static AzureApplication createAzureApplicationFromGraphApplication(Application graphApp, AzureApplication azureApp) {
        GenericUtil.ensureNotNull(graphApp, "Graph-Application cannot be null");
        azureApp.setObjectId(graphApp.id);
        azureApp.setDisplayName(graphApp.displayName);
        azureApp.setDescription(graphApp.description);
        azureApp.setPublisher(graphApp.publisherDomain);
        azureApp.setIsDeviceOnlyAuthSupported(graphApp.isDeviceOnlyAuthSupported);
        azureApp.setDisabledByMicrosoftStatus(graphApp.disabledByMicrosoftStatus);
        azureApp.setPublisherDomain(graphApp.publisherDomain);
        azureApp.setAzureCreatedDateTime(graphApp.createdDateTime);
        azureApp.setTags(graphApp.tags);
        azureApp.setSyncedAt(new Date());
        return azureApp;
    }

    public static AzureAppRoles createAzureAppRolesFromGraphAppRoles(AppRole graphAppRole, AzureAppRoles azureAppRole) {
        GenericUtil.ensureNotNull(graphAppRole, "Graph-AppRole cannot be null");
        azureAppRole.setAzureId(graphAppRole.id);
        azureAppRole.setDisplayName(graphAppRole.displayName);
        azureAppRole.setDescription(graphAppRole.description);
        azureAppRole.setIsEnabled(graphAppRole.isEnabled);
        azureAppRole.setOrigin(graphAppRole.origin);
        azureAppRole.setValue(graphAppRole.value);
        azureAppRole.setSyncedAt(new Date());
        return azureAppRole;
    }

    public static AzureUser createAzureUserFromGraphUser(User user, AzureUser azureUser) {
        GenericUtil.ensureNotNull(user, "Graph-User cannot be null");
        azureUser.setAzureId(user.id);
        azureUser.setDisplayName(user.displayName);
        azureUser.setGivenName(user.givenName);
        azureUser.setSurname(user.surname);
        azureUser.setAccountEnabled(user.accountEnabled);
        azureUser.setMail(user.mail);
        azureUser.setUserPrincipalName(user.userPrincipalName);
        azureUser.setMobilePhone(user.mobilePhone);
        azureUser.setJobTitle(user.jobTitle);
        azureUser.setDepartment(user.department);
        azureUser.setUserType(user.userType);
        azureUser.setOfficeLocation(user.officeLocation);
        azureUser.setCreatedDateTime(user.createdDateTime);
        azureUser.setSyncedAt(new Date());
        azureUser.setIsSSOEnabled(true);
        return azureUser;
    }

    public static AzureGroup createAzureGroupFromGraphGroup(Group graphGroup, AzureGroup azureGroup) {
        GenericUtil.ensureNotNull(graphGroup, "Graph-Group cannot be null");
        azureGroup.setAzureId(graphGroup.id);
        azureGroup.setDisplayName(graphGroup.displayName);
        azureGroup.setDescription(graphGroup.description);
        azureGroup.setMail(graphGroup.mail);
        azureGroup.setMailNickname(graphGroup.mailNickname);
        azureGroup.setMailEnabled(graphGroup.mailEnabled);
        azureGroup.setSecurityEnabled(graphGroup.securityEnabled);
        azureGroup.setVisibility(graphGroup.visibility);
        azureGroup.setAzureCreatedDateTime(graphGroup.createdDateTime);
        azureGroup.setSecurityIdentifier(graphGroup.securityIdentifier);
        azureGroup.setSyncedAt(new Date());
        return azureGroup;
    }

    public static AzureDevice createAzureDeviceFromGraphDevice(Device graphDevice, AzureDevice azureDevice) {
        GenericUtil.ensureNotNull(graphDevice, "Graph-Device cannot be null");
        azureDevice.setAzureId(graphDevice.id);
        azureDevice.setDeviceId(graphDevice.deviceId);
        azureDevice.setDisplayName(graphDevice.displayName);
        azureDevice.setOperatingSystem(graphDevice.operatingSystem);
        azureDevice.setOperatingSystemVersion(graphDevice.operatingSystemVersion);
        azureDevice.setAccountEnabled(Boolean.TRUE.equals(graphDevice.accountEnabled));
        azureDevice.setDeviceVersion(graphDevice.deviceVersion);
        azureDevice.setAzureRegistrationDateTime(graphDevice.registrationDateTime);
        azureDevice.setSyncedAt(new Date());
        return azureDevice;
    }

    public static AzureRoleAssignment createAzureRoleAssignmentFromResourceEntity(RoleAssignment roleAssignment, AzureRoleAssignment azureRoleAssignment) {
        GenericUtil.ensureNotNull(roleAssignment, "RoleAssignment cannot be null");
        azureRoleAssignment.setAzureId(roleAssignment.name());
        azureRoleAssignment.setAzureRoleAssignmentPathId(roleAssignment.id());
        azureRoleAssignment.setAzureRoleDefinitionPathId(roleAssignment.roleDefinitionId());
        azureRoleAssignment.setDescription(roleAssignment.description());
        azureRoleAssignment.setAssignee(roleAssignment.principalId());
        azureRoleAssignment.setPrincipalType(GenericUtil.getOrNull(() -> roleAssignment.innerModel().principalType().getValue()));
        azureRoleAssignment.setScope(roleAssignment.scope());
        azureRoleAssignment.setScopeType(GenericUtil.determineScopeType(roleAssignment.scope()));
        azureRoleAssignment.setCondition(roleAssignment.condition());
        azureRoleAssignment.setIsRoleInherited(false);
        azureRoleAssignment.setCreatedOn(GenericUtil.getOrNull(() -> roleAssignment.innerModel().createdOn()));
        azureRoleAssignment.setCreatedBy(GenericUtil.getOrNull(() -> roleAssignment.innerModel().createdBy()));
        azureRoleAssignment.setUpdatedOn(GenericUtil.getOrNull(() -> roleAssignment.innerModel().updatedOn()));
        azureRoleAssignment.setUpdatedBy(GenericUtil.getOrNull(() -> roleAssignment.innerModel().updatedBy()));
        azureRoleAssignment.setSyncedAt(new Date());
        return azureRoleAssignment;
    }

    public static CustomRoleAssignment createFromAssignRoleRequestPayload(AssignRoleRequest request) {
        GenericUtil.ensureNotNull(request, "Payload cannot be null");
        CustomRoleAssignment customRoleAssignment = new CustomRoleAssignment();
        customRoleAssignment.setAzureId(UUID.randomUUID().toString());
        customRoleAssignment.setScope(request.getResourceScope().trim());
        customRoleAssignment.setAzureRoleDefinitionPathId(request.getRoleDefinitionPathId().trim());
        customRoleAssignment.setAssignee(request.getPrincipleId().trim());
        customRoleAssignment.setPrincipalType(request.getPrincipleType().getValue());
        customRoleAssignment.setScopeType(GenericUtil.determineScopeType(request.getResourceScope()));
        customRoleAssignment.setDescription(GenericUtil.getOrNull(() -> request.getDescription().trim()));
        customRoleAssignment.setStatus(RequestStatus.PENDING);
        customRoleAssignment.setRequestedAt(new Date());
        customRoleAssignment.setExpiryTimeAmount(request.getExpiryTimeAmount());
        customRoleAssignment.setUserEmail(request.getUserEmail().trim());
        customRoleAssignment.setSubscriptionId(request.getSubscriptionId().trim());
        customRoleAssignment.setWsTenantName(request.getTenantName().trim());
        return customRoleAssignment;
    }

    public static CustomRoleAssignment createFromAssignRoleRequestPayload(AssignRoleRequest request, String rolePathId) {
        GenericUtil.ensureNotNull(request, "Payload cannot be null");
        CustomRoleAssignment customRoleAssignment = new CustomRoleAssignment();
        customRoleAssignment.setAzureId(UUID.randomUUID().toString());
        customRoleAssignment.setScope(request.getResourceScope().trim());
        customRoleAssignment.setAzureRoleDefinitionPathId(rolePathId);
        customRoleAssignment.setAssignee(request.getPrincipleId().trim());
        customRoleAssignment.setPrincipalType(request.getPrincipleType().getValue());
        customRoleAssignment.setScopeType(GenericUtil.determineScopeType(request.getResourceScope()));
        customRoleAssignment.setDescription(GenericUtil.getOrNull(() -> request.getDescription().trim()));
        customRoleAssignment.setStatus(RequestStatus.PENDING);
        customRoleAssignment.setRequestedAt(new Date());
        customRoleAssignment.setExpiryTimeAmount(request.getExpiryTimeAmount());
        customRoleAssignment.setUserEmail(request.getUserEmail().trim());
        customRoleAssignment.setSubscriptionId(request.getSubscriptionId().trim());
        customRoleAssignment.setWsTenantName(request.getTenantName().trim());
        return customRoleAssignment;
    }


    public static PublishedResource createPublishedResourcesFromRequest(PublishResourceRequest request) {
        PublishedResource resource = new PublishedResource();
        resource.setResourceId(request.getResourceId().trim());
        resource.setWsTenantName(request.getWsTenantName().trim());
        resource.setResourceType(request.getType());
        resource.setCloudProviderType(CloudProviderType.AZURE);
        return resource;
    }
}
