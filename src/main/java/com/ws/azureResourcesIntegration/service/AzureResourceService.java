package com.ws.azureResourcesIntegration.service;


import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureGroupRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.constant.StateChangeConstants;
import com.ws.azureResourcesIntegration.dto.*;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import com.ws.mapper.AzureEntitiesMapper;
import io.micrometer.common.util.StringUtils;
import com.ws.azureAdIntegration.constants.Constant;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Azure Resource feature service
 */
@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureResourceService {
    final AzureVMRepository azureVMRepository;
    final AzureStorageRepository azureStorageRepository;
    final AzureServerRepository azureServerRepository;
    final AzureDatabaseRepository azureDatabaseRepository;
    final AzureRoleDefinitionRepository azureRoleDefinitionRepository;
    final AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository;
    final AzureRoleAssignmentRepository azureRoleAssignmentRepository;
    final AzureUserRepository azureUserRepository;
    final AzureGroupRepository azureGroupRepository;
    final CustomRoleAssignmentRepository customRoleAssignmentRepository;
    final AzureSubscriptionRepository azureSubscriptionRepository;
    final AzureADService azureADService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserCredentialService azureUserCredentialService;

    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository,
                                AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureUserRepository azureUserRepository,
                                AzureGroupRepository azureGroupRepository, CustomRoleAssignmentRepository customRoleAssignmentRepository, AzureSubscriptionRepository azureSubscriptionRepository, AzureADService azureADService,
                                AzureAuthUtil azureAuthUtil, AzureUserCredentialService azureUserCredentialService) {
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureDatabaseRepository = azureDatabaseRepository;
        this.azureRoleDefinitionRepository = azureRoleDefinitionRepository;
        this.azureRoleDefinitionActionRepository = azureRoleDefinitionActionRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.customRoleAssignmentRepository = customRoleAssignmentRepository;
        this.azureSubscriptionRepository = azureSubscriptionRepository;
        this.azureADService = azureADService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserCredentialService = azureUserCredentialService;
    }

    public List<?> getAzureResourcesUsingType(String wsTenantName, AzureResourcesType type) {
        switch (type) {
            case VM:
                return azureVMRepository.findAllByWsTenantName(wsTenantName);
            case STORAGE_ACCOUNT:
                return azureStorageRepository.findAllByWsTenantName(wsTenantName);
            case SERVER:
                return azureServerRepository.findAllByWsTenantName(wsTenantName);
            case SUBSCRIPTION:
                return azureSubscriptionRepository.findAllByWsTenantName(wsTenantName);
            default:
                throw new RuntimeException("Invalid azure resource type provided: " + type);
        }
    }


    public List<Map<String, Object>> getRoleDefinitionsNameWithId(String tenantName) {
        List<AzureRoleDefinition> azureRoleDefinitions = azureRoleDefinitionRepository.findAllByAzureTenant(azureADService.getAzureTenantUsingWsTenantName(tenantName));
        if (CollectionUtils.isEmpty(azureRoleDefinitions)) {
            return Collections.emptyList();
        }
        return azureRoleDefinitions.stream()
                .map(azureRoleDefinition -> {
                    Map<String, Object> map = Stream.of(
                            new AbstractMap.SimpleEntry<>("id", azureRoleDefinition.getId()),
                            new AbstractMap.SimpleEntry<>("roleName", azureRoleDefinition.getRoleName()),
                            new AbstractMap.SimpleEntry<>("roleType", azureRoleDefinition.getRoleType())
                    ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
                    return map;
                })
                .collect(Collectors.toList());
    }

    public AzureRoleDefinitionDTO getAzureRoleDefinitionDetailsUsingId(Integer azureRoleId, String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
        AzureRoleDefinition azureRoleDefinition = azureRoleDefinitionRepository.findByIdAndAzureTenant(azureRoleId, azureTenant)
                .orElseThrow(() -> new RuntimeException("No Azure Role found with provided id: " + azureRoleId));

        AzureRoleDefinitionDTO response = AzureRoleDefinitionDTO.builder()
                .id(azureRoleDefinition.getId())
                .azureId(azureRoleDefinition.getAzureId())
                .rolePathId(azureRoleDefinition.getRolePathId())
                .roleName(azureRoleDefinition.getRoleName())
                .roleType(azureRoleDefinition.getRoleType())
                .description(azureRoleDefinition.getDescription())
                .assignableScope(azureRoleDefinition.getAssignableScope())
                .syncedAt(azureRoleDefinition.getSyncedAt())
                .wsTenantName(azureRoleDefinition.getWsTenantName())
                .build();

        List<AzureRoleDefinitionActionNameProjection> roleActions = azureRoleDefinitionActionRepository.findAllAzureRoleDefinitionActionNamesByAzureTenantId(azureRoleDefinition.getId(), azureTenant.getWsTenantName());
        if (!CollectionUtils.isEmpty(roleActions)) {
            Map<Boolean, List<String>> partitionedActions = roleActions.stream()
                    .collect(Collectors.partitioningBy(
                            roleAction -> "ACTION".equalsIgnoreCase(roleAction.getActionType()),
                            Collectors.mapping(AzureRoleDefinitionActionNameProjection::getActionName, Collectors.toList())
                    ));
            response.setActions(partitionedActions.get(true));
            response.setNotActions(partitionedActions.get(false));
        }

        return response;
    }


    public List<AzureRolePrincipleAssociationResponse> getAllUsersAssociatedWithRoleId(String wsRoleId, String wsTenantName, String principleType) {
        return PrincipalType.USER.getValue().equalsIgnoreCase(principleType)
                ? azureUserRepository.getAzureUserNameAndIdAssociatedWithRoleDefinitionId(wsRoleId, wsTenantName)
                : PrincipalType.GROUP.getValue().equalsIgnoreCase(principleType)
                ? azureGroupRepository.getAzureUserNameAndIdAssociatedWithRoleId(wsRoleId, wsTenantName)
                : Collections.emptyList();
    }


    public List<?> getAzureAzureResourcesForPrinciple(AzureResourcesType scopeType, String principleType, String assignee, String wsTenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(wsTenantName);
        return switch (scopeType) {
            case VM ->
                    azureVMRepository.getAzureVMsForPrinciple(scopeType.name(), principleType, assignee, azureTenant.getWsTenantName());
            case STORAGE_ACCOUNT ->
                    azureStorageRepository.getAzureStorageAccountsForPrinciple(scopeType.name(), principleType, assignee, azureTenant.getWsTenantName());
            case SERVER ->
                    azureServerRepository.getAzureServersWithDatabasesForPrinciple(Arrays.asList(AzureResourcesType.SERVER.name(), AzureResourcesType.DATABASE.name()), principleType, assignee, azureTenant.getWsTenantName());
            default ->
                    throw new RuntimeException(String.format("Invalid type(s) provided. Check %s and %s values", scopeType, principleType));
        };
    }

    @Transactional
    public void publishResourceByResourceIdAndType(Integer resourceId, AzureResourcesType type) {
        switch (type) {
            case VM:
                getByIdAndPublish(resourceId, azureVMRepository, type);
                break;
            case STORAGE_ACCOUNT:
                getByIdAndPublish(resourceId, azureStorageRepository, type);
                break;
            case DATABASE:
                getByIdAndPublish(resourceId, azureDatabaseRepository, type);
                break;
            default:
                throw new RuntimeException(String.format("Invalid type: %s provided", type));
        }
    }

    public List<?> getPublishedResources(String wsTenantName, AzureResourcesType type) {
        return switch (type) {
            case VM -> azureVMRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
            case STORAGE_ACCOUNT -> azureStorageRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
            case DATABASE -> azureDatabaseRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
            default -> throw new RuntimeException(String.format("Invalid type: %s provided", type));
        };
    }

    private <T> void getByIdAndPublish(Integer resourceId, CrudRepository<T, Integer> repository, AzureResourcesType type) {
        T resource = repository.findById(resourceId)
                .orElseThrow(() -> new RuntimeException(String.format("No resource of type %s found with provided resource id: %s", type, resourceId)));
        updateCommonFields(resource, repository);
    }

    private <T> void updateCommonFields(T resource, CrudRepository<T, Integer> repository) {
        if (resource instanceof BaseAzureResource baseResource) {
            baseResource.setIsPublished(!baseResource.getIsPublished());
            baseResource.setUpdatedAt(new Date());
        }
        repository.save(resource);
    }


    /* JIT FEATURE */
    // 1. API to get the suitable roles for the target resource ✅
    // 2. Raise the request ✅
    // 3. Get all raised requests (of all types) ✅
    // 4. DENY | APPROVE actions -> if approved then call Azure_API for transaction ✅
    public List<ApplicableRoleDefinition> getAllApplicableRoleDefinitionsForResource(Integer resourceId, AzureResourcesType type) {
        Triple<String, String, String> idTriplet = switch (type) {
            case VM -> {
                AzureVM vm = azureVMRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Triple.of(vm.getWsTenantName(), vm.getAzureSubscription().getAzureSubscriptionId(), vm.getResourceType());
            }
            case STORAGE_ACCOUNT -> {
                AzureStorageAccount storageAccount = azureStorageRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Triple.of(storageAccount.getWsTenantName(), storageAccount.getAzureSubscription().getAzureSubscriptionId(), storageAccount.getResourceType());
            }
            case DATABASE -> {
                AzureDatabase database = azureDatabaseRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Triple.of(database.getWsTenantName(), database.getAzureServer().getAzureSubscription().getAzureSubscriptionId(), database.getResourceType());
            }
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type.name());
        };
        Optional.ofNullable(idTriplet.getMiddle()).filter(StringUtils::isNotEmpty).orElseThrow(() -> {
            log.error("No parentSubscriptionId found with provided data from User side. Value: {}", idTriplet.getMiddle());
            return new RuntimeException("Invalid details provided..");
        });

        return Optional.ofNullable(azureRoleDefinitionRepository.findAllSuitableRolesForResource(idTriplet.getLeft(), idTriplet.getRight(), String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, idTriplet.getMiddle())))
                .filter(resultSets -> !resultSets.isEmpty())
                .map(resultSets -> resultSets.stream()
                        .map(resultSet -> ApplicableRoleDefinition.builder()
                                .azureRolePathId(resultSet.getAzureRolePathId())
                                .roleName(resultSet.getRoleName())
                                .roleType(resultSet.getRoleType())
                                .actionList(new ArrayList<>(Arrays.asList(resultSet.getActionList().split(","))))
                                .build())
                        .collect(Collectors.toList()))
                .orElseThrow(() -> new RuntimeException("No data found"));
    }


    @Transactional
    public CustomRoleAssignment raiseResourceAssignmentRequest(AssignRoleRequest request) {
        return customRoleAssignmentRepository.saveOrUpdate(
                request.getPrincipleId().trim(),
                request.getResourceScope().trim(),
                request.getRoleDefinitionPathId().trim(),
                UUID.randomUUID().toString(),
                request.getPrincipleType().getValue(),
                GenericUtil.determineScopeType(request.getResourceScope()),
                request.getDescription(),
                CustomRoleAssignmentStatus.REQUESTED.toString(),
                OffsetDateTime.now(),
                request.getExpiryTimeAmount(),
                request.getTenantName().trim(),
                request.getSubscriptionId());
//        CustomRoleAssignment customRoleAssignment = AzureEntityUtil.createCustomRoleAssignmentFromAssignRoleRequestPayload(request,
//                CustomRoleAssignment.builder()
//                        .azureTenant(azureADService.getAzureTenantUsingWsTenantName(request.getTenantName().trim()))
//                        .build());
//        return customRoleAssignmentRepository.save(customRoleAssignment);
    }


    public Collection<?> getAllRaisedRoleAssignmentRequest(String wsTenantName, CustomRoleAssignmentStatus status) {
        List<CustomRoleAssignment> customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(wsTenantName, status);
        if (CustomRoleAssignmentStatus.APPROVED.equals(status)) {
            Map<String, CustomRoleAssignment> customRoleAssignmentMap = new LinkedHashMap<>();
            customRoleAssignments.forEach(customRoleAssignment -> customRoleAssignmentMap.put(customRoleAssignment.getAzureId(), customRoleAssignment));
            List<CustomRoleAssignment> mappedCustomRoleAssignments = AzureEntitiesMapper.INSTANCE.fromAzureRoleAssignments(azureRoleAssignmentRepository.findAllByWsTenantNameOrderByCreatedOnDesc(wsTenantName));
            mappedCustomRoleAssignments.stream()
                    .filter(customRoleAssignment -> !customRoleAssignmentMap.containsKey(customRoleAssignment.getAzureId()))
                    .forEach(customRoleAssignment -> customRoleAssignmentMap.put(customRoleAssignment.getAzureId(), customRoleAssignment));
            return customRoleAssignmentMap.values();
        }
        return customRoleAssignments;
    }


    /**
     * 1. USE STATE MACHINE TO HANDLE THE ACTION
     * 2. HANDLE THE FAILURE CASE WHERE, AZURE SAVED THE DATA BUT OUR BACKEND FACED ANY ISSUE. Hence we need to call Azure and delete the RA
     */
    @Transactional
    public Boolean processResourceRequestForPrinciple(Integer customRoleAssignmentId, CustomRoleAssignmentStatus updatedStatus) {
        CustomRoleAssignment customRoleAssignment = customRoleAssignmentRepository.findById(customRoleAssignmentId).orElseThrow(() -> new RuntimeException("No raised resource details found with provided id: " + customRoleAssignmentId));
        CustomRoleAssignmentStatus currentStatus = customRoleAssignment.getStatus();

        // Validate the state transition
        if (!StateChangeConstants.CUSTOM_ROLE_ASSIGNMENT_VALID_STATE_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(updatedStatus)) {
            throw new IllegalStateException(String.format("Invalid state transition from %s to %s", currentStatus, updatedStatus));
        }

        switch (updatedStatus) {
            case APPROVED -> processApproval(customRoleAssignment);
            case EXPIRED -> processExpiration(customRoleAssignment);
            case DENIED -> processDenial(customRoleAssignment);
        }

        return true;
    }


    private void processApproval(CustomRoleAssignment customRoleAssignment) {
        RoleAssignment createdRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
        createAzureRoleAssignmentFromRoleAssignment(createdRoleAssignment, customRoleAssignment);
        customRoleAssignment.setAzureRoleAssignmentPathId(createdRoleAssignment.id());
        LocalDateTime validFrom = LocalDateTime.now();
        customRoleAssignment.setValidFrom(validFrom);
        customRoleAssignment.setValidTo(validFrom.plusMinutes(customRoleAssignment.getExpiryTimeAmount()));
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, CustomRoleAssignmentStatus.APPROVED);
    }

    private void processExpiration(CustomRoleAssignment customRoleAssignment) {
        revokeRoleToPrincipalForResourceInAzure(customRoleAssignment.getAzureRoleAssignmentPathId(), customRoleAssignment.getWsTenantName());
        azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(customRoleAssignment.getAzureRoleAssignmentPathId());
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, CustomRoleAssignmentStatus.EXPIRED);
    }

    private void processDenial(CustomRoleAssignment customRoleAssignment) {
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, CustomRoleAssignmentStatus.DENIED);
    }

    private void updateCustomRoleAssignmentCommonFields(CustomRoleAssignment customRoleAssignment, CustomRoleAssignmentStatus status) {
        customRoleAssignment.setStatus(status);
        customRoleAssignment.setUpdatedOn(OffsetDateTime.now());
        customRoleAssignmentRepository.save(customRoleAssignment);
    }

    private RoleAssignment assignRoleToPrincipalForResourceInAzure(String wsTenantName, CustomRoleAssignment customRoleAssignment) {
        try {
            AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName));
            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
                    .roleAssignments()
                    .define(customRoleAssignment.getAzureId())
                    .forObjectId(customRoleAssignment.getAssignee())
                    .withRoleDefinition(customRoleAssignment.getAzureRoleDefinitionPathId())
                    .withScope(customRoleAssignment.getScope())
                    .withDescription(customRoleAssignment.getDescription())
                    .create();
            Optional.ofNullable(createdRoleAssignment).orElseThrow(() -> new RuntimeException("Created RoleAssignment found to be null"));
            return createdRoleAssignment;
        } catch (RuntimeException ex) {
            log.error("Azure error during assigning role. Message: {}", ex.getMessage());
            if (ex.getMessage().contains("403")) {
                throw new RuntimeException("Insufficient privilege. Please review your permissions in Azure");
            }
            throw new RuntimeException("Failed to create role assignment in Azure");
        }
    }

    private void createAzureRoleAssignmentFromRoleAssignment(RoleAssignment roleAssignment, CustomRoleAssignment customRoleAssignment) {
        AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
                roleAssignment, AzureRoleAssignment.builder()
                        .azureSubscription(customRoleAssignment.getAzureSubscription())
                        .wsTenantName(customRoleAssignment.getWsTenantName())
                        .azureTenant(azureADService.getAzureTenantUsingWsTenantName(customRoleAssignment.getWsTenantName()))
                        .build());
        azureRoleAssignmentRepository.save(azureRoleAssignment);
    }

    private void revokeRoleToPrincipalForResourceInAzure(String roleAssignmentId, String wsTenantName) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName));
        azureResourceManager.accessManagement()
                .roleAssignments()
                .deleteById(roleAssignmentId);
        azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(roleAssignmentId);
    }



    /* ------------------------------------------------------------------------------------------------- */

    @Transactional
    public AzureRoleAssignment assignRoleToPrincipalForResourceInAzure(AssignRoleRequest request) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(request.getTenantName()));
        try {
            log.info("Started...");
            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
                    .roleAssignments()
                    .define(UUID.randomUUID().toString())
                    .forObjectId(request.getPrincipleId())
                    .withRoleDefinition(request.getRoleDefinitionPathId())
                    .withScope(request.getResourceScope())
                    .withDescription(request.getDescription())
                    .create();
            log.info("Role Assignment created....");
            if (createdRoleAssignment == null) {
                throw new RuntimeException("Created RoleAssignment found to be null");
            }
            log.info("RA is not null");
            AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
                    createdRoleAssignment, AzureRoleAssignment.builder()
                            .azureRoleDefinitionPathId(createdRoleAssignment.roleDefinitionId())
//                            .subscriptionId(request.getSubscriptionId())
                            .wsTenantName(request.getTenantName())
                            .azureTenant(azureADService.getAzureTenantUsingWsTenantName(request.getTenantName()))
                            .build());
            log.info("Role Assignment saved locally");
            return azureRoleAssignmentRepository.save(azureRoleAssignment);
        } catch (RuntimeException exp) {
            log.error("Error: {}", exp.getMessage());
            throw new RuntimeException(exp.getMessage());
        }
    }

    @Transactional
    public Boolean revokeRoleAssignment(String azureId) {
//        String fullRoleAssignmentId = scope + "/providers/Microsoft.Authorization/roleAssignments/" + roleAssignmentId;
        try {
//            AzureRoleAssignment assignment = azureRoleAssignmentRepository.findByAzureId(azureId).orElseThrow(() -> new RuntimeException("No Role assignment found with provided azure-id: " + azureId));
            AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret("amitdev.local"));
//            azureResourceManager.accessManagement()
//                    .roleAssignments()
//                    .deleteById(assignment.getAzureRoleAssignmentPathId());
//            azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(assignment.getAzureRoleDefinitionPathId());
            return Boolean.TRUE;
        } catch (Exception ex) {
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }


    //    @Transactional
//    public Boolean processResourceRequestForPrinciple(Integer customRoleAssignmentId, CustomRoleAssignmentStatus status) {
//        CustomRoleAssignment customRoleAssignment = customRoleAssignmentRepository.findById(customRoleAssignmentId).orElseThrow(() -> new RuntimeException("No raised resource details found with provided id: " + customRoleAssignmentId));
//        if (status.equals(CustomRoleAssignmentStatus.APPROVED)) {
//            // Call Azure to create RoleAssignment
//            // Copy data into RA table
//            // Then update in CustomRoleAssignment
//            RoleAssignment createdRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
//            createAzureRoleAssignmentFromRoleAssignment(createdRoleAssignment, customRoleAssignment);
//            customRoleAssignment.setAzureRoleAssignmentPathId(createdRoleAssignment.id()); // set the path_id of assigned role
//            customRoleAssignment.setStatus(status);
//            customRoleAssignment.setUpdatedAt(new Date());
//            customRoleAssignment.setValidFrom(new Date());
//            customRoleAssignment.setValidTo(null); // from + time_limit
//            customRoleAssignmentRepository.save(customRoleAssignment);
//            return true;
//        } else if (status.equals(CustomRoleAssignmentStatus.EXPIRED)) {
//            // Call Azure to delete the RA
//            // Delete the row from RA table
//            // Then update in CustomRoleAssignment
//            revokeRoleToPrincipalForResourceInAzure(customRoleAssignment.getAzureRoleAssignmentPathId(), customRoleAssignment.getWsTenantName());
//            azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(customRoleAssignment.getAzureRoleAssignmentPathId());
//            customRoleAssignment.setStatus(status);
//            customRoleAssignment.setUpdatedAt(new Date());
//            customRoleAssignmentRepository.save(customRoleAssignment);
//            return true;
//        } else {
//            // For DENIED
//            // Just do changes in the CustomRoleAssignment
//            customRoleAssignment.setStatus(status);
//            customRoleAssignment.setUpdatedAt(new Date());
//            customRoleAssignmentRepository.save(customRoleAssignment);
//        }
//
//        return null;
//    }

    // --------------------------------------------- //
    // DUmping below apis
//    public List<AzureVM> getAllVirtualMachines(String tenantName) {
//        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
//        return azureVMRepository.findAllByWsTenantName(azureTenant.getWsTenantName());
//    }
//
//    public List<AzureStorageAccount> getStorages(String tenantName) {
//        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
//        return azureStorageRepository.findAllByWsTenantName(azureTenant.getWsTenantName());
//    }
//
//    public List<AzureServer> getServersWithDatavses(String tenantName) {
//        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
//        return azureServerRepository.findAllByWsTenantName(azureTenant.getWsTenantName());
//    }
}





















