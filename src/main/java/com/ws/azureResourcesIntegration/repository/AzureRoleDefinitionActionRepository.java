package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.dto.AzureRoleDefinitionActionNameProjection;
import com.ws.azureResourcesIntegration.entities.AzureRoleDefinitionAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AzureRoleDefinitionActionRepository extends JpaRepository<AzureRoleDefinitionAction, Integer> {
    List<AzureRoleDefinitionAction> findAllByAzureTenant(AzureTenant azureTenant);

    @Query(value = "SELECT arda.\"action\" as actionName, arda.\"type\" as actionType FROM azure_role_definition_action arda " +
            "WHERE arda.ws_azure_role_definition_id = :wsRoleId AND arda.ws_azure_tenant_id  = :azureTenantId "
            , nativeQuery = true)
    List<AzureRoleDefinitionActionNameProjection> findAllAzureRoleDefinitionActionNamesByAzureTenantId(Integer wsRoleId, Integer azureTenantId);
}
