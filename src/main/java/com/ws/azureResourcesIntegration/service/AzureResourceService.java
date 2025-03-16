package com.ws.azureResourcesIntegration.service;


import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.exception.AzureDataException;
import com.ws.azureAdIntegration.repository.AzureGroupRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureTenantService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
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
import com.ws.projection.CustomRoleAssignmentProjection;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    final AzureKubernetesClusterRepository azureKubernetesClusterRepository;
    final PublishedResourcesRepository publishedResourcesRepository;
    final AzureTenantService azureTenantService;
    final AzureAuthUtil azureAuthUtil;
    final AzureUserCredentialService azureUserCredentialService;
    final BackendApplicationLogservice backendApplicationLogservice;


    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository,
                                AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureUserRepository azureUserRepository,
                                AzureGroupRepository azureGroupRepository, CustomRoleAssignmentRepository customRoleAssignmentRepository, AzureSubscriptionRepository azureSubscriptionRepository, AzureUserConfigureRepository azureUserConfigureRepository, AzureKubernetesClusterRepository azureKubernetesClusterRepository, PublishedResourcesRepository publishedResourcesRepository, AzureTenantService azureTenantService,
                                AzureAuthUtil azureAuthUtil, AzureUserCredentialService azureUserCredentialService, BackendApplicationLogservice backendApplicationLogservice) {
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
        this.azureKubernetesClusterRepository = azureKubernetesClusterRepository;
        this.publishedResourcesRepository = publishedResourcesRepository;
        this.azureTenantService = azureTenantService;
        this.azureAuthUtil = azureAuthUtil;
        this.azureUserCredentialService = azureUserCredentialService;
        this.backendApplicationLogservice = backendApplicationLogservice;
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
            case AZURE_KUBERNETES ->
                    azureKubernetesClusterRepository.findAllAzureKubernetesClustersUsingWsTenantName(wsTenantName);
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type);
        };
    }


    public List<RoleDefinitionDTO> getRoleDefinitionsNameWithId(String tenantName) {
        return azureRoleDefinitionRepository.findAllRolesUsingWsTenantName(tenantName);
//        List<AzureRoleDefinition> azureRoleDefinitions = azureRoleDefinitionRepository.findAllByAzureTenant(azureTenantService.getAzureTenantUsingWsTenantName(tenantName));
//        if (CollectionUtils.isEmpty(azureRoleDefinitions)) {
//            return Collections.emptyList();
//        }
//        return azureRoleDefinitions.stream()
//                .map(azureRoleDefinition -> {
//                    Map<String, Object> map = Stream.of(
//                            new AbstractMap.SimpleEntry<>("id", azureRoleDefinition.getId()),
//                            new AbstractMap.SimpleEntry<>("roleName", azureRoleDefinition.getRoleName()),
//                            new AbstractMap.SimpleEntry<>("roleType", azureRoleDefinition.getRoleType())
//                    ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new));
//                    return map;
//                })
//                .collect(Collectors.toList());
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
    public List<ApplicableRoleDefinition> getAllApplicableRoleDefinitionsForResource(Integer resourceId, AzureResourcesType type, String assignee) {
        List<String> scopes;
        Triple<String, String, String> idTriple = switch (type) {
            case VIRTUAL_MACHINE -> {
                AzureVM vm = azureVMRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = vm.getAzureSubscription().getAzureSubscriptionId();
                String rGName = vm.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.VM_LEVEL_SCOPE, subsId, rGName, vm.getName()));
                yield Triple.of(vm.getWsTenantName(), vm.getResourceType(), vm.getAzureVmId());
            }
            case STORAGE_ACCOUNT -> {
                AzureStorageAccount storageAccount = azureStorageRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = storageAccount.getAzureSubscription().getAzureSubscriptionId();
                String rGName = storageAccount.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.STORAGE_ACCOUNT_LEVEL_SCOPE, subsId, rGName, storageAccount.getStorageAccountName()));
                yield Triple.of(storageAccount.getWsTenantName(), storageAccount.getResourceType(), storageAccount.getAzureStorageAccountId());
            }
            case DATABASE -> {
                AzureDatabase database = azureDatabaseRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = database.getAzureServer().getAzureSubscription().getAzureSubscriptionId();
                String rGName = database.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.DATABASE_LEVEL_SCOPE, subsId, rGName, database.getAzureServer().getServerName(), database.getDatabaseName()));
                yield Triple.of(database.getWsTenantName(), database.getResourceType(), database.getAzureDatabaseId());
            }
            case AZURE_KUBERNETES -> {
                AzureKubernetesCluster kubernetesCluster = azureKubernetesClusterRepository.findById(Long.valueOf(resourceId)).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = kubernetesCluster.getAzureSubscription().getAzureSubscriptionId();
                String rGName = kubernetesCluster.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.AKS_LEVEL_SCOPE, subsId, rGName, kubernetesCluster.getName()));
                yield Triple.of(kubernetesCluster.getWsTenantName(), kubernetesCluster.getResourceType(), kubernetesCluster.getAzureId());
            }
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type.name());
        };

        Map<String, String> assignedRolesMap = azureRoleAssignmentRepository.findAllAssignedRolesForPrinciple(assignee, idTriple.getLeft(), idTriple.getRight()).stream()
                .collect(Collectors.toMap(AzureRoleDefinitionDTO::getRolePathId, AzureRoleDefinitionDTO::getRoleName));

        List<ApplicableRoleDefinition> response = azureRoleDefinitionRepository.findAllSuitableRolesForResource(idTriple.getLeft(), idTriple.getMiddle(), scopes.toArray(new String[0]))
                .stream()
                .filter(resultSet -> !assignedRolesMap.containsKey(resultSet.getAzureRolePathId())) // Filter out assigned roles
                .map(resultSet -> ApplicableRoleDefinition.builder()
                        .azureRolePathId(resultSet.getAzureRolePathId())
                        .roleName(resultSet.getRoleName())
                        .roleType(resultSet.getRoleType())
                        .actionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getActionList().split(","))))
                        .notActionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getNotActionList().split(","))))
                        .build())
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(response)) {
            throw new RuntimeException("No data found");
        }

        return response;


//        return Optional.ofNullable(azureRoleDefinitionRepository.findAllSuitableRolesForResource(idTriple.getLeft(), idTriple.getMiddle(), scopes.toArray(new String[0])))
//                .filter(resultSets -> !resultSets.isEmpty())
//                .map(resultSets -> resultSets.stream()
//                        .map(resultSet -> ApplicableRoleDefinition.builder()
//                                .azureRolePathId(resultSet.getAzureRolePathId())
//                                .roleName(resultSet.getRoleName())
//                                .roleType(resultSet.getRoleType())
//                                .actionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getActionList().split(","))))
//                                .notActionList(GenericUtil.getOrEmptyList(() -> Arrays.asList(resultSet.getNotActionList().split(","))))
//                                .flag(StringUtils.isEmpty(assignedRolesMap.get(resultSet.getAzureRolePathId()))) /* fale = User cannot use it */
//                                .build())
//                        .collect(Collectors.toList()))
//                .orElseThrow(() -> new RuntimeException("No data found"));
    }

    @Transactional(readOnly = true)
    public List<ApplicableRoleDefinition> getAllApplicableRoleDefinitionsForResource2(Integer resourceId, AzureResourcesType type) {
        List<String> scopes;
        Triple<String, String, String> idTriple = switch (type) {
            case VIRTUAL_MACHINE -> {
                AzureVM vm = azureVMRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = vm.getAzureSubscription().getAzureSubscriptionId();
                String rGName = vm.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.VM_LEVEL_SCOPE, subsId, rGName, vm.getName()));
                yield Triple.of(vm.getWsTenantName(), vm.getResourceType(), vm.getAzureVmId());
            }
            case STORAGE_ACCOUNT -> {
                AzureStorageAccount storageAccount = azureStorageRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = storageAccount.getAzureSubscription().getAzureSubscriptionId();
                String rGName = storageAccount.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.STORAGE_ACCOUNT_LEVEL_SCOPE, subsId, rGName, storageAccount.getStorageAccountName()));
                yield Triple.of(storageAccount.getWsTenantName(), storageAccount.getResourceType(), storageAccount.getAzureStorageAccountId());
            }
            case DATABASE -> {
                AzureDatabase database = azureDatabaseRepository.findById(resourceId).orElseThrow(() -> new RuntimeException("No resource found with provided id: " + resourceId));
                String subsId = database.getAzureServer().getAzureSubscription().getAzureSubscriptionId();
                String rGName = database.getResourceGroupName();
                scopes = Arrays.asList(String.format(Constant.SUBSCRIPTION_LEVEL_SCOPE, subsId),
                        String.format(Constant.RESOURCE_GROUP_LEVEL_SCOPE, subsId, rGName),
                        String.format(Constant.DATABASE_LEVEL_SCOPE, subsId, rGName, database.getAzureServer().getServerName(), database.getDatabaseName()));
                yield Triple.of(database.getWsTenantName(), database.getResourceType(), database.getAzureDatabaseId());
            }
            default -> throw new RuntimeException("Invalid azure resource type provided: " + type.name());
        };

        return Optional.ofNullable(azureRoleDefinitionRepository.findAllSuitableRolesForResource2(idTriple.getLeft(), idTriple.getMiddle(), scopes.toArray(new String[0])))
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
                    throw new IllegalArgumentException(String.format("Your request is already in %s state for the same resource with the same role", status));
        }
    }

    @Transactional
    public List<CustomRoleAssignment> raiseResourceAssignmentRequestInList(AssignRoleRequest request) {
        if (CollectionUtils.isEmpty(request.getRoleDefinitionPathIds())) {
            throw new AzureDataException("Please provide roles to assign");
        }
        List<CustomRoleAssignment> customRoleAssignments = new ArrayList<>();
        List<CustomRoleAssignment> foundCustomRoles = customRoleAssignmentRepository.findAllByAssigneeAndScopeAndAzureRoleDefinitionPathIdInAndStatusInAndWsTenantName(request.getPrincipleId().trim(), request.getResourceScope().trim(),
                request.getRoleDefinitionPathIds(), Arrays.asList(RequestStatus.APPROVED, RequestStatus.PENDING), request.getTenantName());
        if (CollectionUtils.isEmpty(foundCustomRoles)) {
            request.getRoleDefinitionPathIds().forEach((id) -> customRoleAssignments.add(AzureEntityUtil.createFromAssignRoleRequestPayload(request, id.trim(), RequestStatus.PENDING)));
        } else {
            Set<String> foundRoleIds = foundCustomRoles.stream().map(CustomRoleAssignment::getAzureRoleDefinitionPathId).collect(Collectors.toSet());
            Set<String> uniqueRoleIds = request.getRoleDefinitionPathIds().stream()
                    .filter(requestRoleId -> !foundRoleIds.contains(requestRoleId))
                    .collect(Collectors.toSet());
            if (CollectionUtils.isEmpty(uniqueRoleIds)) {
                throw new AzureDataException(String.format("The following roles have already been requested: %s. Please review your request to avoid duplicate role assignments.", request.getRoleDefinitionPathIds()));
            }
            uniqueRoleIds.forEach((uniqueRoleId) -> customRoleAssignments.add(AzureEntityUtil.createFromAssignRoleRequestPayload(request, uniqueRoleId, RequestStatus.PENDING)));
        }
        return customRoleAssignmentRepository.saveAll(customRoleAssignments);
    }


    private void createdRoleAssignmentForNetworkInterfaces(List<CustomRoleAssignment> customRoleAssignments) {
        customRoleAssignments.forEach(customRoleAssignment -> {
            RoleAssignment nicRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
            createAzureRoleAssignmentFromRoleAssignment(nicRoleAssignment, customRoleAssignment, GenericUtil.determineScopeType(customRoleAssignment.getScope()));
        });
    }

    private List<CustomRoleAssignment> createAndSaveCustomRoleAssignmentForVmNetworkInterfaces(CustomRoleAssignment customRoleAssignment) {
        List<CustomRoleAssignment> customRoleAssignments = new ArrayList<>();
        String wsTenantName = customRoleAssignment.getWsTenantName();
        String assignee = customRoleAssignment.getAssignee();
        Optional<AzureVM> azureVM = azureVMRepository.findAzureVMUsingInstanceId(customRoleAssignment.getScope(), wsTenantName);
        if (azureVM.isPresent()) {
            List<AzureNetworkInterface> networkInterfaces = azureVM.get().getAzureNetworkInterfaces();
            log.info("networkInterfaces size: {}", networkInterfaces.size());
            if (!CollectionUtils.isEmpty(networkInterfaces)) {
                String readerRolePathId = getReaderRoleForWsTenant(wsTenantName);
                if (StringUtils.isEmpty(readerRolePathId)) {
                    log.warn(String.format("No reader role found for the provided wsTenant: %s with VM ID: %s ", wsTenantName, customRoleAssignment.getScope()));
                    return customRoleAssignments;
                }
                log.info("readerRolePathId: {}", readerRolePathId);
                List<String> ids = networkInterfaces.stream().map((AzureNetworkInterface::getAzureId)).toList();
                log.info("ids: {}", ids.size());
                List<CustomRoleAssignment> foundCustomRoleAssignments = customRoleAssignmentRepository.findAllByAssigneeAndAzureRoleDefinitionPathIdAndScopeInAndWsTenantNameAndStatus(assignee, readerRolePathId,
                        ids, wsTenantName, RequestStatus.APPROVED);
                Set<String> foundCustomAssignmentIds = foundCustomRoleAssignments.stream().map(CustomRoleAssignment::getScope).collect(Collectors.toSet());
                log.info("foundCustomAssignmentIds: {}", foundCustomAssignmentIds.size());
                Set<String> uniqueIds = ids.stream()
                        .filter(id -> !foundCustomAssignmentIds.contains(id))
                        .collect(Collectors.toSet());

                log.info("uniqueIds: {}", uniqueIds.size());
                if (!CollectionUtils.isEmpty(uniqueIds)) {
                    customRoleAssignments = uniqueIds.stream()
                            .map(uniqueId -> {
                                CustomRoleAssignment customRoleAssignmentInstance = new CustomRoleAssignment();
                                BeanUtils.copyProperties(customRoleAssignment, customRoleAssignmentInstance, "id"); // Exclude the 'id' property
                                customRoleAssignmentInstance.setScope(uniqueId);
                                customRoleAssignmentInstance.setAzureId(UUID.randomUUID().toString());
                                customRoleAssignmentInstance.setAzureRoleDefinitionPathId(readerRolePathId);
                                customRoleAssignmentInstance.setScopeType(AzureResourcesType.NETWORK_INTERFACE.name());
                                return customRoleAssignmentInstance;
                            })
                            .collect(Collectors.toList());

                    log.info("total saved customRoleAssignments: {}", customRoleAssignments.size());
                    return customRoleAssignmentRepository.saveAll(customRoleAssignments);
                }
            }
        }
        return customRoleAssignments;
    }


    private String getReaderRoleForWsTenant(String wsTenantName) {
        return azureRoleDefinitionRepository.findFirstRoleByPriorityForWsTenant("Reader", "Network Contributor", "Contributor", wsTenantName).orElse(null);
    }


    @Transactional(readOnly = true)
    public List<CustomRoleAssignment> findByAssigneeAndScope(String assignee, String scope) {
        return customRoleAssignmentRepository.findAllByAssigneeAndScope(assignee.trim(), scope.trim());
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
//        backendApplicationLogservice.saveAuditLog(request.getTenantName(), request.getUserEmail(),
//                String.format("Resource access request UPDATED by user: %s for assignee: %s. Current state: %s", request.getUserEmail(), request.getPrincipleId(), RequestStatus.PENDING));
        return customRoleAssignmentRepository.save(customRoleAssignment);
    }

    private CustomRoleAssignment createNewRoleAssignment(AssignRoleRequest request) {
        CustomRoleAssignment newRoleAssignment = AzureEntityUtil.createFromAssignRoleRequestPayload(request);
//        backendApplicationLogservice.saveAuditLog(request.getTenantName(), request.getUserEmail(),
//                String.format("Resource access request CREATED by user: %s for assignee: %s for scope: %s", request.getUserEmail(), request.getPrincipleId(), GenericUtil.determineScopeType(request.getResourceScope())));
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


    public Collection<CustomRoleAssignmentDTO> filterAllByWsTenantNameAndParams(String wsTenantName, RequestStatus status, String userEmail) {
        String azureId = Optional.ofNullable(userEmail)
                .map(email -> azureUserConfigureRepository.findByEmailAndWsTenantName(email.trim(), wsTenantName)
                        .orElseThrow(() -> new RuntimeException("No data found for provided email: " + email)))
                .map(AzureUserConfigure::getAzureId)
                .orElse(null);
        String statusStr = null;
        if (status != null) {
            statusStr = status.name();
        }
        List<CustomRoleAssignmentProjection> customRoleAssignments = customRoleAssignmentRepository.filterAllByWsTenantNameAndParams(wsTenantName, statusStr, azureId);
        return customRoleAssignments.stream()
                .map(customRoleAssignment -> CustomRoleAssignmentDTO.builder()
                        .wsTenantName(customRoleAssignment.getWsTenantName())
                        .requestedAt(customRoleAssignment.getRequestedAt())
                        .resourceName(GenericUtil.extractLastValue(customRoleAssignment.getScope()))
                        .assigneeName(customRoleAssignment.getDisplayName())
                        .assignee(customRoleAssignment.getAssignee())
                        .userEmail(customRoleAssignment.getUserEmail())
                        .status(RequestStatus.valueOf(customRoleAssignment.getStatus()))
                        .roleNames(customRoleAssignment.getRoles())
                        .assignmentIds(customRoleAssignment.getAssignmentIds())
                        .expiryTimeAmount(customRoleAssignment.getExpirtyTime())
                        .validFrom(customRoleAssignment.getValidFrom())
                        .validTo(customRoleAssignment.getValidTo())
                        .build())
                .collect(Collectors.toList());
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

//        // Validate the state transition
//        if (!StateChangeConstants.CUSTOM_ROLE_ASSIGNMENT_VALID_STATE_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(updatedStatus)) {
//            throw new IllegalStateException(String.format("Invalid state transition from %s to %s", currentStatus, updatedStatus));
//        }
//
//        switch (updatedStatus) {
//            case APPROVED -> processApproval(customRoleAssignment);
//            case EXPIRED -> processExpiration(customRoleAssignment);
//            case DECLINE -> processDenial(customRoleAssignment);
//        }

        processRequest(customRoleAssignment, updatedStatus, currentStatus);
        return true;
    }


    @Transactional
    public Boolean processResourceRequestForPrinciple(ProcessAccessRequest request, String wsTenantName) {
        if (ObjectUtils.isEmpty(request)) {
            throw new RuntimeException("Please provide payload");
        }
        List<CustomRoleAssignment> customRoleAssignments = customRoleAssignmentRepository.findAllByWsTenantNameAndAzureIdIn(wsTenantName, request.getAssignmentIds());
        if (CollectionUtils.isEmpty(customRoleAssignments)) {
            throw new RuntimeException("No data found for provided IDs and tenant detail");
        }
        customRoleAssignments.forEach(customRoleAssignment -> processRequest(customRoleAssignment, request.getStatus(), customRoleAssignment.getStatus()));
        return Boolean.TRUE;
    }

    private void processRequest(CustomRoleAssignment customRoleAssignment, RequestStatus updatedStatus, RequestStatus currentStatus) {
        // Validate the state transition
        if (!StateChangeConstants.VALID_STATE_TRANSITIONS.getOrDefault(currentStatus, Set.of()).contains(updatedStatus)) {
            throw new IllegalStateException(String.format("Invalid state transition from %s to %s for resource %s for the user %s",
                    currentStatus, updatedStatus, GenericUtil.determineScopeType(customRoleAssignment.getScope()), customRoleAssignment.getUserEmail()));
        }

        switch (updatedStatus) {
            case APPROVED -> processApproval(customRoleAssignment);
            case EXPIRED -> processExpiration(customRoleAssignment);
            case DECLINE -> processDenial(customRoleAssignment);
        }
    }

    private void processApproval(CustomRoleAssignment customRoleAssignment) {
        RoleAssignment createdRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
        createAzureRoleAssignmentFromRoleAssignment(createdRoleAssignment, customRoleAssignment, GenericUtil.determineScopeType(customRoleAssignment.getScope()));
        customRoleAssignment.setAzureRoleAssignmentPathId(createdRoleAssignment.id());
        LocalDateTime validFrom = LocalDateTime.now();
        customRoleAssignment.setValidFrom(validFrom);
        customRoleAssignment.setValidTo(validFrom.plusMinutes(customRoleAssignment.getExpiryTimeAmount()));
        updateCustomRoleAssignmentCommonFields(customRoleAssignment, RequestStatus.APPROVED);
        if (AzureResourcesType.VIRTUAL_MACHINE.equals(AzureResourcesType.valueOf(customRoleAssignment.getScopeType()))) {
            List<CustomRoleAssignment> customRoleAssignments = createAndSaveCustomRoleAssignmentForVmNetworkInterfaces(customRoleAssignment);
            createdRoleAssignmentForNetworkInterfaces(customRoleAssignments);
        }
    }


    /**
     * LEGACY CODE
     */
//    private void processApproval(CustomRoleAssignment customRoleAssignment) {
//        RoleAssignment createdRoleAssignment = assignRoleToPrincipalForResourceInAzure(customRoleAssignment.getWsTenantName(), customRoleAssignment);
//        createAzureRoleAssignmentFromRoleAssignment(createdRoleAssignment, customRoleAssignment);
//        customRoleAssignment.setAzureRoleAssignmentPathId(createdRoleAssignment.id());
//        LocalDateTime validFrom = LocalDateTime.now();
//        customRoleAssignment.setValidFrom(validFrom);
//        customRoleAssignment.setValidTo(validFrom.plusMinutes(customRoleAssignment.getExpiryTimeAmount()));
////        backendApplicationLogservice.saveAuditLog(customRoleAssignment.getWsTenantName(), customRoleAssignment.getUserEmail(),
////                String.format("Resource access request %S and Azure Role Assignment CREATED in Azure for the assignee: %s", RequestStatus.APPROVED, customRoleAssignment.getAssignee()));
//        updateCustomRoleAssignmentCommonFields(customRoleAssignment, RequestStatus.APPROVED);
//    }
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


    private void createAzureRoleAssignmentFromRoleAssignment(RoleAssignment roleAssignment, CustomRoleAssignment customRoleAssignment, String scopeType) {
        AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
                roleAssignment, scopeType, AzureRoleAssignment.builder()
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


    public void revokeRoleToPrincipalForResourceInAzure(String wsTenantName, String pathId) {
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName));
//        log.info("total: {}", azureResourceManager.storageAccounts().list().stream().count());
        try {
            azureResourceManager.accessManagement()
                    .roleAssignments()
                    .deleteById(pathId);
        } catch (Exception exp) {
            if (exp.getMessage().contains("404")) {
                log.error("No data found for provided Role in Azure");
            }
            throw exp;
        }
    }





    /* ------------------------------------------------------------------------------------------------- */

    @Transactional
    public void assignRoleToPrincipalForResourceInAzure(AssignRoleRequest request) {
//        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(request.getTenantName()));
        AzureResourceManager azureResourceManager = azureAuthUtil.validateAzureCredentialsWithSubscriptionId(azureUserCredentialService.findWSTenantIdWithDecryptedSecret("amitdev.local"));
//        String id = UUID.randomUUID().toString();
//        String assignee = "c30c5b8e-f883-42a9-a7ff-4d16cd0f7ec8";
//        String roleId1 = "/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/providers/Microsoft.Authorization/roleDefinitions/0f37683f-2463-46b6-9ce7-9b788b988ba2";
//        String roleId2 = "/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/providers/Microsoft.Authorization/roleDefinitions/c025889f-8102-4ebf-b32c-fc0c6f0c6bd9";
//        String scope = "/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/resourceGroups/centos-test01_group/providers/Microsoft.Storage/storageAccounts/whiteswanstorage";
//
//        try {
//            log.info("For roleId1");
//            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
//                    .roleAssignments()
//                    .define(id)
//                    .forObjectId(assignee)
//                    .withRoleDefinition(roleId1)
//                    .withScope(scope)
//                    .create();
//        } catch (Exception e) {
//            log.error("Failure for 1");
//            log.error("message1: {}", e.getMessage());
//        }
//        log.info("Success1");
//
//        try {
//            log.info("For roleId2");
//            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
//                    .roleAssignments()
//                    .define(id)
//                    .forObjectId(assignee)
//                    .withRoleDefinition(roleId2)
//                    .withScope(scope)
//                    .create();
//        } catch (Exception e) {
//            log.error("Failure for 2");
//            log.error("message2: {}", e.getMessage());
//        }
//        log.info("Success2");
        try {
            log.info("Started...");
            RoleAssignment createdRoleAssignment = azureResourceManager.accessManagement()
                    .roleAssignments()
                    .define(UUID.randomUUID().toString())
                    .forObjectId("c30c5b8e-f883-42a9-a7ff-4d16cd0f7ec8")
                    .withRoleDefinition("/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/providers/Microsoft.Authorization/roleDefinitions/4abbcc35-e782-43d8-92c5-2d3f1bd2253f")
                    .withScope("/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/resourcegroups/ws-test-aks-rg/providers/Microsoft.ContainerService/managedClusters/ws-test-aks-cluster-1")
                    .withDescription("AKS cluster user access")
                    .create();
            log.info("Role Assignment created....");
            if (createdRoleAssignment == null) {
                throw new RuntimeException("Created RoleAssignment found to be null");
            }
            log.info("RA is not null");
//            AzureRoleAssignment azureRoleAssignment = AzureEntityUtil.createAzureRoleAssignmentFromResourceEntity(
//                    createdRoleAssignment, GenericUtil.determineScopeType(createdRoleAssignment.scope()), AzureRoleAssignment.builder()
//                            .azureRoleDefinitionPathId(createdRoleAssignment.roleDefinitionId())
////                            .subscriptionId(request.getSubscriptionId())
//                            .wsTenantName(request.getTenantName())
//                            .azureTenant(azureTenantService.getAzureTenantUsingWsTenantName(request.getTenantName()))
//                            .build());
//            log.info("Role Assignment saved locally");
//            return azureRoleAssignmentRepository.save(azureRoleAssignment);
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





















