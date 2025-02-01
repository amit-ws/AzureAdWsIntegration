package com.ws.azureResourcesIntegration.service;


import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureGroupRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureAdIntegration.service.AzureTenantService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.util.AzureAuthUtil;
import com.ws.azureAdIntegration.util.AzureEntityUtil;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import com.ws.azureResourcesIntegration.constant.StateChangeConstants;
import com.ws.azureResourcesIntegration.dto.*;
import com.ws.azureResourcesIntegration.entities.*;
import com.ws.azureResourcesIntegration.repository.*;
import com.ws.azureAdIntegration.constants.Constant;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
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
    final AzureUserConfigureRepository azureUserConfigureRepository;
    final PublishedResourcesRepository publishedResourcesRepository;
    final AzureTenantService azureTenantService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserCredentialService azureUserCredentialService;


    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository,
                                AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureUserRepository azureUserRepository,
                                AzureGroupRepository azureGroupRepository, CustomRoleAssignmentRepository customRoleAssignmentRepository, AzureSubscriptionRepository azureSubscriptionRepository, AzureUserConfigureRepository azureUserConfigureRepository, PublishedResourcesRepository publishedResourcesRepository, AzureTenantService azureTenantService,
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
        this.azureUserConfigureRepository = azureUserConfigureRepository;
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.azureTenantService = azureTenantService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserCredentialService = azureUserCredentialService;
    }

    public List<?> getAzureResourcesUsingType(String wsTenantName, AzureResourcesType type) {
        return switch (type) {
            case VIRTUAL_MACHINE -> azureVMRepository.findAllByWsTenantName(wsTenantName);
            case STORAGE_ACCOUNT -> azureStorageRepository.findAllByWsTenantName(wsTenantName);
            case SERVER -> azureServerRepository.findAllByWsTenantName(wsTenantName);
            case SUBSCRIPTION -> azureSubscriptionRepository.findAllByWsTenantName(wsTenantName);
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type);
        };
    }


    public List<?> getAzureResourcesUsingType(AzureResourcesType type, String wsTenantName) {
        return switch (type) {
            case VIRTUAL_MACHINE -> azureVMRepository.findAllAzureVMUsingTenantName(wsTenantName);
            case STORAGE_ACCOUNT -> azureStorageRepository.findAllAzureStorageAccountsUsingTenantName(wsTenantName);
            case DATABASE -> azureDatabaseRepository.findAllAzureDatabasesUsingWsTenantName(wsTenantName);
            case SUBSCRIPTION -> azureSubscriptionRepository.findAllByWsTenantName(wsTenantName);
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type);
        };
    }


    public List<Map<String, Object>> getRoleDefinitionsNameWithId(String tenantName) {
        List<AzureRoleDefinition> azureRoleDefinitions = azureRoleDefinitionRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(tenantName));
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
        AzureTenant azureTenant = azureTenantService.getAzureTenantUsingWsTenantName(tenantName);
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
        AzureTenant azureTenant = azureTenantService.getAzureTenantUsingWsTenantName(wsTenantName);
        return switch (scopeType) {
            case VIRTUAL_MACHINE ->
                    azureVMRepository.getAzureVMsForPrinciple(scopeType.name(), principleType, assignee, azureTenant.getWsTenantName());
            case STORAGE_ACCOUNT ->
                    azureStorageRepository.getAzureStorageAccountsForPrinciple(scopeType.name(), principleType, assignee, azureTenant.getWsTenantName());
            case SERVER ->
                    azureServerRepository.getAzureServersWithDatabasesForPrinciple(Arrays.asList(AzureResourcesType.SERVER.name(), AzureResourcesType.DATABASE.name()), principleType, assignee, azureTenant.getWsTenantName());
            default ->
                    throw new RuntimeException(String.format("Invalid type(s) provided. Check %s and %s values", scopeType, principleType));
        };
    }

//    @Transactional
//    public void publishResourceByResourceIdAndType(Integer resourceId, AzureResourcesType type) {
//        switch (type) {
//            case VIRTUAL_MACHINE:
//                getByIdAndPublish(resourceId, azureVMRepository, type);
//                break;
//            case STORAGE_ACCOUNT:
//                getByIdAndPublish(resourceId, azureStorageRepository, type);
//                break;
//            case DATABASE:
//                getByIdAndPublish(resourceId, azureDatabaseRepository, type);
//                break;
//            default:
//                throw new RuntimeException(String.format("Invalid type: %s provided", type));
//        }
//    }


    @Transactional
    public void publishResourceByResourceIdAndType(PublishResourceRequest request) {
        if (request.isFlag()) {
            try {
                publishedResourcesRepository.save(AzureEntityUtil.createPublishedResourcesFromRequest(request));
            } catch (Exception ex) {
                if (ex.getMessage().contains("duplicate key")) {
                    throw new RuntimeException("Resource already published");
                }
            }
        } else {
            publishedResourcesRepository.deleteByResourceIdAndWsTenantName(request.getAzureId(), request.getWsTenantName());
        }
    }


//    public void validateRequestType(PublishResourceType type) {
//        if (type == null || !EnumUtils.isValidEnum(PublishResourceType.class, type.name())) {
//            throw new IllegalArgumentException("Invalid or Unsupported resource type provided in the request.");
//        }
//    }


//    public List<?> getPublishedResourcesV1(String wsTenantName, AzureResourcesType type) {
//        List<?> resources = switch (type) {
//            case VIRTUAL_MACHINE -> azureVMRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
//            case STORAGE_ACCOUNT -> azureStorageRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
//            case DATABASE -> azureDatabaseRepository.findAllByWsTenantNameAndIsPublishedTrue(wsTenantName);
//            default -> throw new RuntimeException(String.format("Invalid type: %s provided", type));
//        };
//        if (!resources.isEmpty()) {
//            setSubscriptionIdForResources(resources);
//        }
//        return resources;
//    }

    public List<?> getPublishedResources(String wsTenantName, AzureResourcesType type) {
        List<?> resources = switch (type) {
            case VIRTUAL_MACHINE -> azureVMRepository.findAllPublishedAzureVM(wsTenantName);
            case STORAGE_ACCOUNT -> azureStorageRepository.findAllPublishedAzureStorageAccounts(wsTenantName);
            case DATABASE -> azureDatabaseRepository.findAllPublishedAzureDatabase(wsTenantName);
            default -> throw new RuntimeException(String.format("Invalid type provided. Type: %s", type));
        };
        if (!resources.isEmpty()) {
            setSubscriptionIdForResources(resources);
        }
        return resources;
    }

    private void setSubscriptionIdForResources(List<?> resources) {
        if (resources.get(0) instanceof AzureVM) {
            Integer subscriptionId = ((AzureVM) resources.get(0)).getAzureSubscription().getId();
            resources.forEach(resource -> ((AzureVM) resource).setAzureSubscriptionId(subscriptionId));
        } else if (resources.get(0) instanceof AzureStorageAccount) {
            Integer subscriptionId = ((AzureStorageAccount) resources.get(0)).getAzureSubscription().getId();
            resources.forEach(resource -> ((AzureStorageAccount) resource).setAzureSubscriptionId(subscriptionId));
        } else if (resources.get(0) instanceof AzureDatabase) {
            Integer subscriptionId = ((AzureDatabase) resources.get(0)).getAzureServer().getAzureSubscription().getId();
            resources.forEach(resource -> ((AzureDatabase) resource).setAzureSubscriptionId(subscriptionId));
        }
    }


//    private <T> void getByIdAndPublish(Integer resourceId, CrudRepository<T, Integer> repository, AzureResourcesType type) {
//        T resource = repository.findById(resourceId)
//                .orElseThrow(() -> new RuntimeException(String.format("No resource of type %s found with provided resource id: %s", type, resourceId)));
//        updateCommonFields(resource, repository);
//    }

//    private <T> void updateCommonFields(T resource, CrudRepository<T, Integer> repository) {
//        if (resource instanceof BaseAzureResource baseResource) {
//            baseResource.setIsPublished(Optional.ofNullable(baseResource.getIsPublished()).map(p -> !p).orElse(true));
//            baseResource.setUpdatedAt(new Date());
//        }
//        repository.save(resource);
//    }


    /* JIT FEATURE */
    // 1. API to get the suitable roles for the target resource ✅
    // 2. Raise the request ✅
    // 3. Get all raised requests (of all types) ✅
    // 4. DENY | APPROVE actions -> if approved then call Azure_API for transaction ✅

    @Transactional(readOnly = true)
    public List<ApplicableRoleDefinition> getAllApplicableRoleDefinitionsForResource(Integer resourceId, AzureResourcesType type) {
        List<String> scopes;
        Pair<String, String> idPair = switch (type) {
            case VIRTUAL_MACHINE -> {
                AzureVM vm = azureVMRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = vm.getAzureSubscription().getAzureSubscriptionId();
                String rGName = vm.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.VM_LEVEL_SCOPE, subsId, rGName, vm.getName()));
                yield Pair.of(vm.getWsTenantName(), vm.getResourceType());
            }
            case STORAGE_ACCOUNT -> {
                AzureStorageAccount storageAccount = azureStorageRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = storageAccount.getAzureSubscription().getAzureSubscriptionId();
                String rGName = storageAccount.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.STORAGE_ACCOUNT_LEVEL_SCOPE, subsId, rGName, storageAccount.getStorageAccountName()));
                yield Pair.of(storageAccount.getWsTenantName(), storageAccount.getResourceType());
            }
            case DATABASE -> {
                AzureDatabase database = azureDatabaseRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = database.getAzureServer().getAzureSubscription().getAzureSubscriptionId();
                String rGName = database.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.DATABASE_LEVEL_SCOPE, subsId, rGName, database.getAzureServer().getServerName(), database.getDatabaseName()));
                yield Pair.of(database.getWsTenantName(), database.getResourceType());
            }
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type.name());
        };

        return Optional.ofNullable(azureRoleDefinitionRepository.findAllSuitableRolesForResource(idPair.getLeft(), idPair.getRight(), scopes.toArray(new String[0])))
                .filter(resultSets -> !resultSets.isEmpty())
                .map(resultSets -> resultSets.stream()
                        .map(resultSet -> ApplicableRoleDefinition.builder()
                                .azureRolePathId(resultSet.getAzureRolePathId())
                                .roleName(resultSet.getRoleName())
                                .roleType(resultSet.getRoleType())
                                .actionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getActionList().split(","))))
                                .notActionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getNotActionList().split(","))))
                                .build())
                        .collect(Collectors.toList()))
                .orElseThrow(() -> new RuntimeException("No data found"));
    }

    @Transactional
    public CustomRoleAssignment raiseResourceAssignmentRequest(AssignRoleRequest request) {
        Optional<CustomRoleAssignment> existingRoleAssignment = customRoleAssignmentRepository.findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNotIn(
                request.getPrincipleId().trim(), request.getResourceScope().trim(), request.getRoleDefinitionPathId().trim(), Arrays.asList(RequestStatus.DECLINE, RequestStatus.EXPIRED));
        return existingRoleAssignment.map(roleAssignment -> handleExistingRoleAssignment(roleAssignment, request))
                .orElseGet(() -> createNewRoleAssignment(request));
    }

    public CustomRoleAssignment raiseResourceAssignmentRequestV2(AssignRoleRequest request) {
        Optional<CustomRoleAssignment> existingRoleAssignmentOpt = customRoleAssignmentRepository.findByAssigneeAndScopeAndAzureRoleDefinitionPathIdAndStatusNotIn(
                request.getPrincipleId().trim(), request.getResourceScope().trim(), request.getRoleDefinitionPathId().trim(), Arrays.asList(RequestStatus.DECLINE, RequestStatus.EXPIRED));
        existingRoleAssignmentOpt.ifPresent(this::handleExistingRoleAssignment);
        return createNewRoleAssignment(request);
    }


    private void handleExistingRoleAssignment(CustomRoleAssignment customRoleAssignment) {
        RequestStatus status = customRoleAssignment.getStatus();
        switch (status) {
            case PENDING, APPROVED ->
                    throw new IllegalArgumentException(String.format("Your request is already in %s state", status));
        }
    }

    private CustomRoleAssignment handleExistingRoleAssignment(CustomRoleAssignment roleAssignment, AssignRoleRequest request) {
        RequestStatus status = roleAssignment.getStatus();
        return switch (status) {
            case PENDING, APPROVED ->
                    throw new IllegalArgumentException(String.format("Your request is already in %s state", status));
            default -> updateCustomRoleAssignment(roleAssignment, request);
        };
    }

    private CustomRoleAssignment updateCustomRoleAssignment(CustomRoleAssignment customRoleAssignment, AssignRoleRequest request) {
        customRoleAssignment.setStatus(RequestStatus.PENDING);
        customRoleAssignment.setUpdatedAt(new Date());
        customRoleAssignment.setExpiryTimeAmount(request.getExpiryTimeAmount());
        customRoleAssignment.setDescription(GenericUtil.getOrNull(() -> request.getDescription().trim()));
        return customRoleAssignmentRepository.save(customRoleAssignment);
    }

    private CustomRoleAssignment createNewRoleAssignment(AssignRoleRequest request) {
        CustomRoleAssignment newRoleAssignment = AzureEntityUtil.createFromAssignRoleRequestPayload(request);
        return customRoleAssignmentRepository.save(newRoleAssignment);
    }


    public Collection<CustomRoleAssignmentDTO> getAllRaisedRoleAssignmentRequest(String wsTenantName, RequestStatus status, String userEmail) {
        String azureId = Optional.ofNullable(userEmail)
                .map(email -> azureUserConfigureRepository.findByEmailAndWsTenantName(email.trim(), wsTenantName)
                        .orElseThrow(() -> new RuntimeException("No data found for provided email: " + email)))
                .map(AzureUserConfigure::getAzureId)
                .orElse(null);
        List<CustomRoleAssignmentDTO> customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndStatus2(wsTenantName, status, azureId);
//        if (RequestStatus.APPROVED.equals(status) || status == null) {
//            Map<String, CustomRoleAssignmentDTO> customRoleAssignmentMap = new LinkedHashMap<>();
//            customRoleAssignments.forEach(customRoleAssignment -> customRoleAssignmentMap.put(customRoleAssignment.getAzureId(), customRoleAssignment));
//            List<CustomRoleAssignmentDTO> mappedCustomRoleAssignments = azureRoleAssignmentRepository.findAllByWsTenantNameAndAssignee(wsTenantName, azureId);
//            mappedCustomRoleAssignments.forEach(customRoleAssignment -> customRoleAssignment.setStatus(RequestStatus.APPROVED));
//            mappedCustomRoleAssignments.stream()
//                    .filter(customRoleAssignment -> !customRoleAssignmentMap.containsKey(customRoleAssignment.getAzureId()))
//                    .forEach(customRoleAssignment -> customRoleAssignmentMap.put(customRoleAssignment.getAzureId(), customRoleAssignment));
//            log.info("1 size: {}", customRoleAssignmentMap.values().size());
//            setResourceName(customRoleAssignmentMap.values());
//            return customRoleAssignmentMap.values();
//        }
        log.info("2 size: {}", customRoleAssignments.size());
        setResourceName(customRoleAssignments);
        return customRoleAssignments;
    }

    private void setResourceName(Collection<CustomRoleAssignmentDTO> dtos) {
        dtos.forEach((dto -> {
            dto.setResourceName(GenericUtil.extractLastValue(dto.getScope()));
        }));
    }


//    public Collection<?> getAllRaisedRoleAssignmentRequestALL(String wsTenantName, RequestStatus status) {
//        List<CustomRoleAssignment> customRoleAssignments;
//
//        if (status == null) {
//            customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameOrderByCreatedOnDesc(wsTenantName);
//        } else {
//            customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndStatusOrderByCreatedOnDesc(wsTenantName, status);
//        }
//
//        // Process the customRoleAssignments
//        if (RequestStatus.APPROVED.equals(status) || status == null) {
//            Collection<CustomRoleAssignment> assignments = new ArrayList<>();
//            List<CustomRoleAssignment> approvedAssignments = new ArrayList<>();
//            Map<String, CustomRoleAssignment> assignmentMap = new LinkedHashMap<>();
//
//            customRoleAssignments.forEach(customRoleAssignment -> {
//                if (customRoleAssignment.getStatus().equals(RequestStatus.APPROVED)) {
//                    approvedAssignments.add(customRoleAssignment);
//                } else {
//                    assignments.add(customRoleAssignment);
//                }
//            });
//
//            List<CustomRoleAssignment> mappedCustomRoleAssignments = AzureEntitiesMapper.INSTANCE.fromAzureRoleAssignments(azureRoleAssignmentRepository.findAllByWsTenantNameOrderByCreatedOnDesc(wsTenantName));
//
//            // Add approved assignments to map
//            approvedAssignments.forEach(customRoleAssignment -> assignmentMap.put(customRoleAssignment.getAzureId(), customRoleAssignment));
//
//            // Directly add mapped custom role assignments to the map if not already present
//            mappedCustomRoleAssignments.forEach(customRoleAssignment -> {
//                assignmentMap.putIfAbsent(customRoleAssignment.getAzureId(), customRoleAssignment);
//            });
//
//            // Add all the entries from the map directly to assignments
//            assignments.addAll(assignmentMap.values());
//            log.info("1 sizxe: {}", assignments.size());
//            return assignments;
//        } else {
//            // Directly add the custom role assignments for the given status
//            log.info("2 sizxe: {}", customRoleAssignments.size());
//            return customRoleAssignments;
//        }
//    }


    /**
     * 1. USE STATE MACHINE TO HANDLE THE ACTION
     * 2. HANDLE THE FAILURE CASE WHERE, AZURE SAVED THE DATA BUT OUR BACKEND FACED ANY ISSUE. Hence we need to call Azure and delete the RA
     */
    @Transactional
    public Boolean processResourceRequestForPrinciple(Integer customRoleAssignmentId, RequestStatus updatedStatus) {
        CustomRoleAssignment customRoleAssignment = customRoleAssignmentRepository.findById(customRoleAssignmentId).orElseThrow(() -> new RuntimeException("No raised resource details found with provided id: " + customRoleAssignmentId));
        RequestStatus currentStatus = customRoleAssignment.getStatus();

        // Validate the state transition
        if (!StateChangeConstants.CUSTOM_ROLE_ASSIGNMENT_VALID_STATE_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(updatedStatus)) {
            throw new IllegalStateException(String.format("Invalid state transition from %s to %s", currentStatus, updatedStatus));
        }

        switch (updatedStatus) {
            case APPROVED -> processApproval(customRoleAssignment);
            case EXPIRED -> processExpiration(customRoleAssignment);
            case DECLINE -> processDenial(customRoleAssignment);
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
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, RequestStatus.APPROVED);
    }

    @Transactional
    public void revokeAzureResourceAccess(CustomRoleAssignment customRoleAssignment) {
        processExpiration(customRoleAssignment);
    }

    public void revokeAzureResourceAccess(List<CustomRoleAssignment> customRoleAssignments, AzureUserCredentialDTO azureUserCredentialDTO) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialDTO);
        revokeRoleToPrincipalForResourceInAzure(customRoleAssignments, azureResourceManager);
    }


    private void processExpiration(CustomRoleAssignment customRoleAssignment) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(customRoleAssignment.getWsTenantName()));
        revokeRoleToPrincipalForResourceInAzure(customRoleAssignment.getAzureRoleAssignmentPathId(), azureResourceManager);
        azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(customRoleAssignment.getAzureRoleAssignmentPathId());
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, RequestStatus.EXPIRED);
    }

    private void processDenial(CustomRoleAssignment customRoleAssignment) {
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, RequestStatus.DECLINE);
    }

    private void updateCustomRoleAssignmentCommonFields(CustomRoleAssignment customRoleAssignment, RequestStatus status) {
        customRoleAssignment.setStatus(status);
        customRoleAssignment.setUpdatedAt(new Date());
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
            } else if (ex.getMessage().contains("RoleAssignmentExists")) {
                throw new RuntimeException("The RoleAssignment already exists in Azure. Please allow some time for the changes to be reflected then try again");
            }
            throw new RuntimeException("Unexpected Error: " + ex.getMessage());
        }
    }

    private void createAzureRoleAssignmentFromRoleAssignment(RoleAssignment roleAssignment, CustomRoleAssignment customRoleAssignment) {
        AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
                roleAssignment, AzureRoleAssignment.builder()
                        .wsTenantName(customRoleAssignment.getWsTenantName())
                        .azureSubscription(findAzureSubscriptionByAzureIdAndWsTenantName(customRoleAssignment.getSubscriptionId(), customRoleAssignment.getWsTenantName()))
                        .azureTenant(azureTenantService.getAzureTenantUsingWsTenantName(customRoleAssignment.getWsTenantName()))
                        .build());
        azureRoleAssignmentRepository.save(azureRoleAssignment);
    }

    private AzureSubscription findAzureSubscriptionByAzureIdAndWsTenantName(String subscriptionId, String wsTenantName) {
        return azureSubscriptionRepository.findByAzureSubscriptionIdAndWsTenantName(subscriptionId, wsTenantName)
                .orElseThrow(() -> new RuntimeException(String.format("Invalid subscription Id found: %s", subscriptionId)));

    }

    private void revokeRoleToPrincipalForResourceInAzure(String roleAssignmentPathId, AzureResourceManager azureResourceManager) {
        try {
            azureResourceManager.accessManagement()
                    .roleAssignments()
                    .deleteById(roleAssignmentPathId);
        } catch (Exception e) {
            if (e.getMessage().contains("404")) {
                log.error("No data found for provided Role in Azure");
                log.info("Role-path-id: {}", roleAssignmentPathId);
            }
        }
    }


    private void revokeRoleToPrincipalForResourceInAzure(List<CustomRoleAssignment> customRoleAssignments, AzureResourceManager azureResourceManager) {
        customRoleAssignments
                .forEach(customRoleAssignment -> revokeRoleToPrincipalForResourceInAzure(customRoleAssignment.getAzureRoleAssignmentPathId(), azureResourceManager));
    }


    public void revokeRoleToPrincipalForResourceInAzure(String wsTenantName) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName));
        log.info("total: {}", azureResourceManager.storageAccounts().list().stream().count());
        try {
            azureResourceManager.accessManagement()
                    .roleAssignments()
                    .deleteById("/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/resourceGroups/centos-test01_group/providers/Microsoft.Storage/storageAccounts/whiteswanstorage/providers/Microsoft.Authorization/roleAssignments/0bc96654-891a-4692-a011-c5441055956f");
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.error("No data found for provided Role in Azure");
            }
            throw exp;
        }
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
                            .azureTenant(azureTenantService.getAzureTenantUsingWsTenantName(request.getTenantName()))
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
            AzureRoleAssignment assignment = azureRoleAssignmentRepository.findByAzureId(azureId).orElseThrow(() -> new RuntimeException("No Role assignment found with provided azure-id: " + azureId));
            log.info("tenant: {}", assignment.getWsTenantName());
            log.info("azure id: {}", assignment.getAzureId());
            log.info("RA path id: {}", assignment.getAzureRoleAssignmentPathId());
            AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(assignment.getWsTenantName()));
            log.info("started");
            azureResourceManager.accessManagement()
                    .roleAssignments()
                    .deleteById(assignment.getAzureRoleAssignmentPathId());
            azureRoleAssignmentRepository.deleteByAzureRoleAssignmentPathId(assignment.getAzureRoleAssignmentPathId());
            log.info("deleted");
            return Boolean.TRUE;
        } catch (Exception ex) {
            log.error("Error: {}", ex.getMessage());
            if (ex.getMessage().contains("401")) {
                throw new RuntimeException("UnAuthorized access token");
            }
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





















