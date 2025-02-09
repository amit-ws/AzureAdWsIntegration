package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8NamespaceRole;
import com.ws.azureKuberntesJIT.projection.K8RoleProjection;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface K8NamespaceRoleRepository extends JpaRepository<K8NamespaceRole, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(nr.id, nr.uid, nr.name, nr.namespace, nr.clusterId, nr.cloudProviderType, nr.parentResourceId) " +
            "FROM K8NamespaceRole nr WHERE nr.wsTenantName = :wsTenantName and nr.cloudProviderType = :cloudProviderType")
    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudType(String wsTenantName, CloudProviderType cloudProviderType);


    @Query(value = "SELECT  " +
            "    knr.id, knr.uid, knr.\"name\", knr.\"namespace\", knr.cloud_provider_type AS CloudType, knr.cluster_id AS ClusterId,  " +
            "    knr.parent_resource_id AS ParentResourceId, " +
            "    STRING_AGG(kprv.verb, ', ') AS verbs, " +
            "    STRING_AGG(kprag.api_group, ', ') AS apiGroups, " +
            "    STRING_AGG(kprr.resource, ', ') AS resources, " +
            "    STRING_AGG(kprrn.resource_name, ', ') AS resourceNames " +
            "FROM kubernetes_namespace_role  knr  " +
            "INNER JOIN kubernetes_policy_rule kpr ON knr.id = kpr.cluster_role_id  " +
            "INNER JOIN kubernetes_policy_rule_verbs kprv ON kprv.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_api_groups kprag ON kprag.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_resources kprr ON kprr.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_resource_names kprrn ON kprrn.policy_rule_id = kpr.id  " +
            "WHERE knr.ws_tenant_name = :wsTenantName AND knr.id = :roleUID " +
            "GROUP BY knr.id, knr.uid, knr.\"name\", knr.\"namespace\", knr.cloud_provider_type, knr.cluster_id",
            nativeQuery = true)
    Optional<K8RoleProjection> getK8NamespaceRoleUsingUidAndTenantNameAndCloudType(String wsTenantName, String roleUID);
}
