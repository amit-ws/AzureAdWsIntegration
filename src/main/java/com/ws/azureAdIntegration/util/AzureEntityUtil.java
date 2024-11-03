package com.ws.azureAdIntegration.util;

import com.microsoft.graph.models.*;
import com.ws.azureAdIntegration.entity.*;

import java.util.Date;

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
        azureUser.setOfficeLocation(user.officeLocation);
        azureUser.setSyncedAt(new Date());
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
}
