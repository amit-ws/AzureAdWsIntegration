package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8ClusterRole;
import com.ws.azureKuberntesJIT.projection.K8RoleProjection;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface K8ClusterRoleRepository extends JpaRepository<K8ClusterRole, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(cr.id, cr.uid, cr.name, cr.namespace, cr.clusterId, cr.cloudProviderType, cr.parentResourceId) " +
            "FROM K8ClusterRole cr WHERE cr.wsTenantName = :wsTenantName and cr.cloudProviderType = :cloudProviderType")
    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudType(String wsTenantName, CloudProviderType cloudProviderType);


    @Query(value = "SELECT  " +
            "    kcr.id, kcr.uid, kcr.\"name\", kcr.\"namespace\", kcr.cloud_provider_type AS CloudType, kcr.cluster_id AS ClusterId,  " +
            "    kcr.parent_resource_id AS ParentResourceId, " +
            "    STRING_AGG(kprv.verb, ', ') AS verbs, " +
            "    STRING_AGG(kprag.api_group, ', ') AS apiGroups, " +
            "    STRING_AGG(kprr.resource, ', ') AS resources, " +
            "    STRING_AGG(kprrn.resource_name, ', ') AS resourceNames " +
            "FROM kubernetes_cluster_role kcr  " +
            "INNER JOIN kubernetes_policy_rule kpr ON kcr.id = kpr.cluster_role_id  " +
            "INNER JOIN kubernetes_policy_rule_verbs kprv ON kprv.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_api_groups kprag ON kprag.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_resources kprr ON kprr.policy_rule_id = kpr.id  " +
            "INNER JOIN kubernetes_policy_rule_resource_names kprrn ON kprrn.policy_rule_id = kpr.id  " +
            "WHERE kcr.ws_tenant_name = :wsTenantName AND kcr.uid = :roleUID  " +
            "GROUP BY kcr.id, kcr.uid, kcr.\"name\", kcr.\"namespace\", kcr.cloud_provider_type, kcr.cluster_id", nativeQuery = true)
    Optional<K8RoleProjection> getK8ClusterRoleUsingUidAndTenantNameAndCloudType(String wsTenantName, String roleUID);
}
