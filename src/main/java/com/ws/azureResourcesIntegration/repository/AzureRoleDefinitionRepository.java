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
            "WITH rd_id AS (" +
                    "  SELECT DISTINCT ws_azure_role_definition_id AS id " +
                    "  FROM azure_role_definition_action a " +
                    "  WHERE a.ws_tenant_name = :tenantName " +
                    "    AND upper(a.\"action\") ILIKE ANY (ARRAY[CONCAT(upper(:action), '/%'), '*'])" +
                    ") " +
                    "SELECT ard.role_path_id AS azureRolePathId, " +
                    "       ard.role_name AS roleName, " +
                    "       ard.role_type AS roleType, " +
                    "       STRING_AGG(arda.\"action\", ', ') AS actionList " +
                    "FROM rd_id " +
                    "INNER JOIN azure_role_definition ard ON rd_id.id = ard.id " +
                    "INNER JOIN azure_role_definition_assignable_scopes ardas ON rd_id.id = ardas.ws_azure_role_definition_id " +
                    "INNER JOIN azure_role_definition_action arda ON rd_id.id = arda.ws_azure_role_definition_id " +
                    "WHERE ( " +
                    "   ardas.assignable_scope = ANY (ARRAY[:assignableScope]::text[]) " +
                    "   OR ardas.assignable_scope = '/' " +
                    ") " +
                    "GROUP BY ard.id, ard.role_name, ard.role_type " +
                    "ORDER BY ard.role_name",
            nativeQuery = true)
    List<ApplicableRoleDefinitionProjection> findAllSuitableRolesForResource(@Param("tenantName") String wsTenantName, @Param("action") String action, @Param("assignableScope") List<String> assignableScope);

}
