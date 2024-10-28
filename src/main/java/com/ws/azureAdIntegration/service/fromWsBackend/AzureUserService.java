//package com.ws.azureAdIntegration.service.fromWsBackend;
//
//import com.whiteswansecurity.uihost.domain.azure.*;
//import com.whiteswansecurity.uihost.repository.azure.*;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//
//@Service
//@Slf4j
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AzureUserService {
//    final AzureUserRepository userRepository;
//    final AzureApplicationRepository applicationRepository;
//    final AzureAppRolesRepository appRolesRepository;
//    final AzureTenantRepository tenantRepository;
//    final AzureGroupRepository groupRepository;
//    final AzureUserGroupMembershipRepository userGroupMembershipRepository;
//    final AzureDeviceRepository azureDeviceRepository;
//    final AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository;
//
//    @Autowired
//    public AzureUserService(AzureUserRepository userRepository, AzureApplicationRepository applicationRepository, AzureAppRolesRepository appRolesRepository,
//                            AzureTenantRepository tenantRepository, AzureGroupRepository groupRepository, AzureUserGroupMembershipRepository userGroupMembershipRepository, AzureDeviceRepository azureDeviceRepository, AzureUserDeviceRelationshipRepository azureUserDeviceRelationshipRepository) {
//        this.userRepository = userRepository;
//        this.applicationRepository = applicationRepository;
//        this.appRolesRepository = appRolesRepository;
//        this.tenantRepository = tenantRepository;
//        this.groupRepository = groupRepository;
//        this.userGroupMembershipRepository = userGroupMembershipRepository;
//        this.azureDeviceRepository = azureDeviceRepository;
//        this.azureUserDeviceRelationshipRepository = azureUserDeviceRelationshipRepository;
//    }
//
//    public List<AzureUser> fetchUsers() {
//        return userRepository.findAll();
//    }
//
//    public List<AzureGroup> fetchGroups() {
//        return groupRepository.findAll();
//    }
//
//    public List<AzureApplication> fetchApplications() {
//        return applicationRepository.findAll();
//    }
//
//    public List<AzureAppRoles> fetchAppRoles() {
//        return appRolesRepository.findAll();
//    }
//
//    public AzureTenant fetchTenant(String azuredId) {
//        Optional<AzureTenant> tenantOpt = tenantRepository.findByAzureId(azuredId);
//        return tenantOpt.isPresent() ? tenantOpt.get() : null;
//    }
//
//    public List<Map<String, String>> fetchUserGroupMembership() {
//        return userGroupMembershipRepository.fetchUsersGroupsMembership();
//    }
//
//    public List<AzureDevice> fetchAzureAdDevices() {
//        return azureDeviceRepository.findAll();
//    }
//
//    public List<Map<String, String>> fetchAzureUserDeviceMapping(){
//        return azureUserDeviceRelationshipRepository.fetchAzureUserDeviceMappings();
//    }
//
//}
