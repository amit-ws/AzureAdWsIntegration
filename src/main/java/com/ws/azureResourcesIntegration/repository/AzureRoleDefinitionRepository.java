package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureRoleDefinition;
import com.ws.projection.ApplicableRoleDefinitionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AzureRoleDefinitionRepository extends JpaRepository<AzureRoleDefinition, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);

    List<AzureRoleDefinition> findAllByAzureTenant(AzureTenant azureTenant);

    Optional<AzureRoleDefinition> findByIdAndAzureTenant(Integer id, AzureTenant azureTenant);

    @Query(value =
            "with rd_id as ( " +
                    "select distinct ws_azure_role_definition_id as id  FROM azure_role_definition_action a WHERE  " +
                    "a.ws_azure_tenant_id = :tenantId and upper(a.\"action\") ILIKE ANY (ARRAY[CONCAT(upper(:action), '/%'), '*'])   " +
                    ") " +
                    "select ard.role_path_id as azureRolePathId, ard.id, ard.role_name as roleName, ard.role_type as roleType, STRING_AGG(arda.\"action\", ', ') AS actionList   " +
                    "from rd_id  " +
                    "inner join azure_role_definition ard on rd_id.id = ard.id  " +
                    "inner join azure_role_definition_assignable_scopes ardas on rd_id.id = ardas.ws_azure_role_definition_id " +
                    "inner join azure_role_definition_action arda on rd_id.id = arda.ws_azure_role_definition_id  " +
                    "where ardas.assignable_scope = ANY (ARRAY[:assignableScope, '/'])  " +
                    "group by ard.id, ard.role_name, ard.role_type " +
                    "order by ard.role_name"
            , nativeQuery = true)
    List<ApplicableRoleDefinitionProjection> findAllSuitableRolesForResource(@Param("tenantId") Integer azureTenantId, String action, String assignableScope);

}
