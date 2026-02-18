package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.dto.RoleDefinitionDTO;
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

    @Query(value = "SELECT new com.ws.azureResourcesIntegration.dto.RoleDefinitionDTO(ard.id, ard.azureId, ard.roleName, ard.roleType, ard.subscriptionId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM AzureRoleDefinition ard " +
            "LEFT JOIN PublishedResource pr ON UPPER(ard.azureId) = UPPER(pr.resourceId) " +
            "WHERE ard.wsTenantName = :wsTenantName AND (:subscriptionId IS NULL OR ard.subscriptionId = :subscriptionId) " +
            "ORDER BY ard.roleName ", nativeQuery = false)
    List<RoleDefinitionDTO> findAllRolesUsingWsTenantNameAndsubscriptionId(String wsTenantName, String subscriptionId);

    Optional<AzureRoleDefinition> findByIdAndAzureTenant(Integer id, AzureTenant azureTenant);

    @Query(value =
            "SELECT DISTINCT  " +
                    "    ard.role_path_id AS azureRolePathId,   " +
                    "    ard.role_name AS roleName,   " +
                    "    ard.role_type AS roleType,   " +
                    "    STRING_AGG(CASE WHEN arda.\"type\" = 'ACTION' THEN arda.\"action\" END, ', ') AS actionList,  " +
                    "    STRING_AGG(CASE WHEN arda.\"type\" = 'NOT ACTION' THEN arda.\"action\" END, ', ') AS notActionList " +
                    "FROM azure_role_definition_action arda   " +
                    "INNER JOIN azure_role_definition ard ON arda.ws_azure_role_definition_id = ard.id   " +
                    "INNER JOIN azure_role_definition_assignable_scopes ardas ON ard.id = ardas.ws_azure_role_definition_id   " +
                    "INNER JOIN published_resource pr on ard.azure_id = pr.resource_id " +
                    "WHERE   " +
                    "    (UPPER(ardas.assignable_scope) = ANY (:assignableScopes) OR ardas.assignable_scope = '/' )   " +
                    "    AND UPPER(arda.\"action\") ILIKE ANY (ARRAY[CONCAT(UPPER(:action), '/%'), '*'])   " +
                    "    AND arda.ws_tenant_name = :wsTenantName  " +
                    "GROUP BY ard.id, ard.role_name, ard.role_type   " +
                    "ORDER BY ard.role_name "
            , nativeQuery = true)
    List<ApplicableRoleDefinitionProjection> findAllSuitableRolesForResource(@Param("wsTenantName") String wsTenantName,
                                                                             @Param("action") String resourceType,
                                                                             @Param("assignableScopes") String[] assignableScopes);

    @Query(value =
            "SELECT DISTINCT  " +
                    "    ard.role_path_id AS azureRolePathId,   " +
                    "    ard.role_name AS roleName,   " +
                    "    ard.role_type AS roleType,   " +
                    "    STRING_AGG(CASE WHEN arda.\"type\" = 'ACTION' THEN arda.\"action\" END, ', ') AS actionList,  " +
                    "    STRING_AGG(CASE WHEN arda.\"type\" = 'NOT ACTION' THEN arda.\"action\" END, ', ') AS notActionList " +
                    "FROM azure_role_definition_action arda   " +
                    "INNER JOIN azure_role_definition ard ON arda.ws_azure_role_definition_id = ard.id   " +
                    "INNER JOIN azure_role_definition_assignable_scopes ardas ON ard.id = ardas.ws_azure_role_definition_id   " +
                    "WHERE   " +
                    "    (ardas.assignable_scope = ANY (:assignableScopes) OR ardas.assignable_scope = '/' )   " +
                    "    AND UPPER(arda.\"action\") ILIKE ANY (ARRAY[CONCAT(UPPER(:action), '/%'), '*'])   " +
                    "    AND arda.ws_tenant_name = :wsTenantName  " +
                    "GROUP BY ard.id, ard.role_name, ard.role_type   " +
                    "ORDER BY ard.role_name "
            , nativeQuery = true)
    List<ApplicableRoleDefinitionProjection> findAllSuitableRolesForResource2(@Param("wsTenantName") String wsTenantName,
                                                                              @Param("action") String resourceType,
                                                                              @Param("assignableScopes") String[] assignableScopes);


    @Query(value = "SELECT ard.role_path_id  " +
            "FROM azure_role_definition ard " +
            "WHERE ard.ws_tenant_name = :wsTenantName " +
            "  AND upper(ard.role_name) IN (upper(:role1), upper(:role2), upper(:role3)) " +
            "ORDER BY  " +
            "  CASE  " +
            "    WHEN upper(ard.role_name) = upper(:role1) THEN 1 " +
            "    WHEN upper(ard.role_name) = upper(:role2) THEN 2 " +
            "    WHEN upper(ard.role_name) = upper(:role3) THEN 3 " +
            "  END " +
            "LIMIT 1",
            nativeQuery = true)
    Optional<String> findFirstRoleByPriorityForWsTenant(String role1, String role2, String role3, String wsTenantName);

}

















