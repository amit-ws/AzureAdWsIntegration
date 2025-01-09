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
import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import com.ws.azureResourcesIntegration.dto.*;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import io.micrometer.common.util.StringUtils;
import com.ws.azureAdIntegration.constants.Constant;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

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
    final AzureADService azureADService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserCredentialService azureUserCredentialService;

    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository,
                                AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureUserRepository azureUserRepository,
                                AzureGroupRepository azureGroupRepository, CustomRoleAssignmentRepository customRoleAssignmentRepository, AzureADService azureADService, AzureAuthUtil azureAuthUtil, AzureUserCredentialService azureUserCredentialService) {
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
        this.azureADService = azureADService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserCredentialService = azureUserCredentialService;
    }

    public List<AzureVM> getAllVirtualMachines(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
        return azureVMRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureStorageAccount> getStorages(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
        return azureStorageRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureServer> getServersWithDatavses(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingWsTenantName(tenantName);
        return azureServerRepository.findAllByAzureTenant(azureTenant);
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

        List<AzureRoleDefinitionActionNameProjection> roleActions = azureRoleDefinitionActionRepository.findAllAzureRoleDefinitionActionNamesByAzureTenantId(azureRoleDefinition.getId(), azureTenant.getId());
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
                    azureVMRepository.getAzureVMsForPrinciple(scopeType.name(), principleType, assignee, azureTenant);
            case STORAGE_ACCOUNT ->
                    azureStorageRepository.getAzureStorageAccountsForPrinciple(scopeType.name(), principleType, assignee, azureTenant);
            case SERVER ->
                    azureServerRepository.getAzureServersWithDatabasesForPrinciple(Arrays.asList(AzureResourcesType.SERVER.name(), AzureResourcesType.DATABASE.name()), principleType, assignee, azureTenant);
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
    // 4. DENY | APPROVE actions -> if approved then call Azure_API for transaction
    public List<ApplicableRoleDefinition> getAllApplicableRoleDefinitionsForResource(String resourceType, Integer resourceId, AzureResourcesType type) {
        Pair<Integer, String> idPair = switch (type) {
            case VM -> {
                AzureVM vm = azureVMRepository.findByIdAndResourceType(resourceId, resourceType).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Pair.of(vm.getAzureTenant().getId(), vm.getAzureSubscription().getAzureSubscriptionId());
            }
            case STORAGE_ACCOUNT -> {
                AzureStorageAccount storageAccount = azureStorageRepository.findByIdAndResourceType(resourceId, resourceType).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Pair.of(storageAccount.getAzureTenant().getId(), storageAccount.getAzureSubscription().getAzureSubscriptionId());
            }
            case DATABASE -> {
                AzureDatabase database = azureDatabaseRepository.findByIdAndResourceType(resourceId, resourceType).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                yield Pair.of(database.getAzureTenant().getId(), database.getAzureServer().getAzureSubscription().getAzureSubscriptionId());
            }
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type.name());
        };
        Optional.ofNullable(idPair.getRight()).filter(StringUtils::isNotEmpty).orElseThrow(() -> {
            log.error("No parentSubscriptionId found with provided data from User side. Value: {}", idPair.getRight());
            return new RuntimeException("Invalid details provided..");
        });

        return Optional.ofNullable(azureRoleDefinitionRepository.findAllSuitableRolesForResource(idPair.getLeft(), resourceType, String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, idPair.getRight())))
                .filter(resultSets -> !resultSets.isEmpty())
                .map(resultSets -> resultSets.stream()
                        .map(resultSet -> ApplicableRoleDefinition.builder()
                                .id(resultSet.getId())
                                .azureRolePathId(resultSet.getAzureRolePathId())
                                .roleName(resultSet.getRoleName())
                                .roleType(resultSet.getRoleType())
                                .actionList(new ArrayList<>(Arrays.asList(resultSet.getActionList().split(","))))
                                .build())
                        .collect(Collectors.toList()))
                .orElseThrow(() -> new RuntimeException("No data found"));
    }


    @Transactional
    public Boolean raiseResourceAssignmentRequest(AssignRoleRequest request) {
        CustomRoleAssignment customRoleAssignment = AzureEntityUtil.createCustomRoleAssignmentFromAssignRoleRequestPayload(request,
                CustomRoleAssignment.builder()
                        .azureTenantId(azureADService.getAzureTenantUsingWsTenantName(request.getTenantName().trim()).getAzureId())
                        .build());
        return ObjectUtils.isNotEmpty(customRoleAssignmentRepository.save(customRoleAssignment));
    }


    public List<CustomRoleAssignment> getAllRaiseRoleAssignmentRequest(String wsTenantName, CustomRoleAssignmentStatus status) {
        return switch (status) {
            case REQUESTED, DENIED, EXPIRED ->
                    customRoleAssignmentRepository.findAllByWsTenantNameAndStatus(wsTenantName, status);
            default ->
                // Get the aggregated data from both tables
                    Collections.emptyList();
        };
    }


    /**
     * 1. USE STATE MACHINE TO HANDLE THE ACTION
     * 2. HANDLE THE FAILURE CASE WHERE, AZURE SAVED THE DATA BUT OUR BACKEND FACED ANY ISSUE. Hence we need to call Azure and delete the RA
     */
    @Transactional
    public Boolean manageResourceRequest(Integer customRoleAssignmentId, CustomRoleAssignmentStatus status) {
        CustomRoleAssignment customRoleAssignment = customRoleAssignmentRepository.findById(customRoleAssignmentId).orElseThrow(() -> new RuntimeException("No raised resource details found with provided id: " + customRoleAssignmentId));
        if (status.equals(CustomRoleAssignmentStatus.APPROVED)) {
            // Call Azure to create RoleAssignment
            // Copy data into RA table
            // Then update in CustomRoleAssignment
            RoleAssignment createdRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
            createAzureRoleAssignmentFromRoleAssignment(createdRoleAssignment, customRoleAssignment);
            customRoleAssignment.setStatus(status);
            customRoleAssignment.setUpdatedAt(new Date());
            customRoleAssignment.setValidFrom(new Date());
            customRoleAssignment.setValidTo(null); // from + time_limit
            customRoleAssignmentRepository.save(customRoleAssignment);
            return true;
        } else if (status.equals(CustomRoleAssignmentStatus.EXPIRED)) {
            // Call Azure to delete the RA
            // Delete the row from RA table
            // Then update in CustomRoleAssignment
            return true;
        } else {
            // For DENIED
            // Just do changes in the CustomRoleAssignment
            customRoleAssignment.setStatus(status);
            customRoleAssignment.setUpdatedAt(new Date());
            customRoleAssignmentRepository.save(customRoleAssignment);
        }

        return null;
    }

    private RoleAssignment assignRoleToPrincipalForResourceInAzure(String wsTenantName, CustomRoleAssignment customRoleAssignment) {
        try {
            AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTeanantIdWithDecryptedSecret(wsTenantName));
            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
                    .roleAssignments()
                    .define(customRoleAssignment.getAzureId())
                    .forObjectId(customRoleAssignment.getAssignee())
                    .withRoleDefinition(customRoleAssignment.getAzureRoleDefinitionId())
                    .withScope(customRoleAssignment.getScope())
                    .withDescription(customRoleAssignment.getDescription())
                    .create();
            Optional.ofNullable(createdRoleAssignment).orElseThrow(() -> new RuntimeException("Created RoleAssignment found to be null"));
            return createdRoleAssignment;
        } catch (RuntimeException ex) {
            log.error("Azure error: {}", ex.getMessage());
            if (ex.getMessage().contains("403")) {
                throw new RuntimeException("Insufficient privilege. Please review your permissions in Azure");
            }
            throw new RuntimeException("Failed to create role assignment in Azure");
        }
    }

    private void createAzureRoleAssignmentFromRoleAssignment(RoleAssignment roleAssignment, CustomRoleAssignment customRoleAssignment) {
        AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
                roleAssignment, AzureRoleAssignment.builder()
                        .azureRoleDefinitionId(customRoleAssignment.getAzureRoleDefinitionId())
                        .subscriptionId(customRoleAssignment.getSubscriptionId())
                        .wsTenantName(customRoleAssignment.getWsTenantName())
                        .azureTenant(azureADService.getAzureTenantUsingWsTenantName(customRoleAssignment.getWsTenantName()))
                        .build());
        azureRoleAssignmentRepository.save(azureRoleAssignment);
    }


    @Transactional
    public AzureRoleAssignment assignRoleToPrincipalForResourceInAzure(AssignRoleRequest request) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTeanantIdWithDecryptedSecret(request.getTenantName()));
        try {
            log.info("Started...");
            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
                    .roleAssignments()
                    .define(UUID.randomUUID().toString())
                    .forObjectId(request.getPrincipleId())
                    .withRoleDefinition(request.getRoleDefinitionId())
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
                            .azureRoleDefinitionId(request.getRoleDefinitionId())
                            .subscriptionId(azureResourceManager.subscriptionId())
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
    public Boolean revokeRoleAssignment(String roleAssignmentId) {
//        String fullRoleAssignmentId = scope + "/providers/Microsoft.Authorization/roleAssignments/" + roleAssignmentId;
        try {
            AzureRoleAssignment assignment = azureRoleAssignmentRepository.findByAzureRoleAssignmentPathId(roleAssignmentId).orElseThrow(() -> new RuntimeException("No Role assignment found with provided id"));
            AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTeanantIdWithDecryptedSecret(assignment.getWsTenantName()));
            azureResourceManager.accessManagement()
                    .roleAssignments()
                    .deleteById(roleAssignmentId);
            azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(roleAssignmentId);
            return Boolean.TRUE;
        } catch (Exception ex) {
            log.error("Error: {}", ex.getMessage());
            throw new RuntimeException(ex.getMessage());
        }
    }
}





















