package com.ws.azureResourcesIntegration.service;


import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureAdIntegration.repository.AzureGroupRepository;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureResourcesIntegration.constant.AzureResourcesType;
import com.ws.azureResourcesIntegration.dto.AzureRoleDefinitionActionNameProjection;
import com.ws.azureResourcesIntegration.dto.AzureRoleDefinitionDTO;
import com.ws.azureResourcesIntegration.dto.AzureRolePrincipleAssociationResponse;
import com.ws.azureResourcesIntegration.entities.AzureRoleDefinition;
import com.ws.azureResourcesIntegration.entities.AzureServer;
import com.ws.azureResourcesIntegration.entities.AzureStorageAccount;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import com.ws.azureResourcesIntegration.repository.*;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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
    final AzureADService azureADService;

    @Autowired
    public AzureResourceService(AzureVMRepository azureVMRepository, AzureStorageRepository azureStorageRepository, AzureServerRepository azureServerRepository, AzureDatabaseRepository azureDatabaseRepository,
                                AzureRoleDefinitionRepository azureRoleDefinitionRepository, AzureRoleDefinitionActionRepository azureRoleDefinitionActionRepository, AzureRoleAssignmentRepository azureRoleAssignmentRepository, AzureUserRepository azureUserRepository,
                                AzureGroupRepository azureGroupRepository, AzureADService azureADService) {
        this.azureVMRepository = azureVMRepository;
        this.azureStorageRepository = azureStorageRepository;
        this.azureServerRepository = azureServerRepository;
        this.azureDatabaseRepository = azureDatabaseRepository;
        this.azureRoleDefinitionRepository = azureRoleDefinitionRepository;
        this.azureRoleDefinitionActionRepository = azureRoleDefinitionActionRepository;
        this.azureRoleAssignmentRepository = azureRoleAssignmentRepository;
        this.azureUserRepository = azureUserRepository;
        this.azureGroupRepository = azureGroupRepository;
        this.azureADService = azureADService;
    }

    public List<AzureVM> getAllVirtualMachines(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureVMRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureStorageAccount> getStorages(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureStorageRepository.findAllByAzureTenant(azureTenant);
    }

    public List<AzureServer> getServersWithDatavses(String tenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        return azureServerRepository.findAllByAzureTenant(azureTenant);
    }

    public List<Map<String, Object>> getRoleDefinitionsNameWithId(String tenantName) {
        List<AzureRoleDefinition> azureRoleDefinitions = azureRoleDefinitionRepository.findAllByAzureTenant(azureADService.getAzureTenantUsingwsTenantEmail(tenantName));
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
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(tenantName);
        AzureRoleDefinition azureRoleDefinition = azureRoleDefinitionRepository.findByIdAndAzureTenant(azureRoleId, azureTenant)
                .orElseThrow(() -> new RuntimeException("No Azure Role found with provided id: " + azureRoleId));

        AzureRoleDefinitionDTO response = AzureRoleDefinitionDTO.builder()
                .id(azureRoleDefinition.getId())
                .azureId(azureRoleDefinition.getAzureId())
                .rolePathId(azureRoleDefinition.getRolePathId())
                .roleName(azureRoleDefinition.getRoleName())
                .roleType(azureRoleDefinition.getRoleType())
                .description(azureRoleDefinition.getDescription())
                .isPublished(azureRoleDefinition.getIsPublished())
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
                ? azureUserRepository.getAzureUserNameAndIdAssociatedWithRoleId(wsRoleId, wsTenantName)
                : PrincipalType.GROUP.getValue().equalsIgnoreCase(principleType)
                ? azureGroupRepository.getAzureUserNameAndIdAssociatedWithRoleId(wsRoleId, wsTenantName)
                : Collections.emptyList();
    }


    public List<?> getAzureVMsForPrinciple(String scopeType, String principleType, String assignee, String wsTenantName) {
        AzureTenant azureTenant = azureADService.getAzureTenantUsingwsTenantEmail(wsTenantName);
        if (AzureResourcesType.VM.name().equalsIgnoreCase(scopeType)) {
            return azureVMRepository.getAzureVMsForPrinciple(scopeType, principleType, assignee, azureTenant);
        } else if (AzureResourcesType.STORAGE_ACCOUNT.name().equalsIgnoreCase(scopeType)) {
            return azureStorageRepository.getAzureStorageAccountsForPrinciple(scopeType, principleType, assignee, azureTenant);
        } else if (AzureResourcesType.SERVER.name().equalsIgnoreCase(scopeType)) {
            return azureServerRepository.getAzureServersWithDatabasesForPrinciple(Arrays.asList(AzureResourcesType.SERVER.name(), AzureResourcesType.DATABASE.name()), principleType, assignee, azureTenant);
        } else {
            throw new RuntimeException(String.format("Invalid type(s) provided. Check %s and %s values", scopeType, principleType));
        }
    }

}






















