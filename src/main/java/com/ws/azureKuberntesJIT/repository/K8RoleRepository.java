package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.enttity.K8Role;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8RoleRepository extends JpaRepository<K8Role, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(kr.id, kr.uid, kr.name, kr.namespace, kr.roleType, kr.clusterId, kr.cloudProviderType, kr.cloudResourceAccountId) " +
            "FROM K8Role kr " +
            "WHERE kr.wsTenantName = :wsTenantName AND kr.cloudProviderType = :cloudProviderType AND (:roleType IS NULL OR kr.roleType = :roleType) " +
            "ORDER BY kr.name")
    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudTypeAndRoleType(String wsTenantName, CloudProviderType cloudProviderType, K8ResourceLevel roleType);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

//    @Query(value = "SELECT   " +
//            "    kr.id, kr.uid, kr.\"name\", kr.\"namespace\", kr.cloud_provider_type AS CloudType, kr.cluster_id AS ClusterId,   " +
//            "    kr.resource_account_id AS resourceAccountId,  " +
//            "    STRING_AGG(kprv.verb, ', ') AS verbs,  " +
//            "    STRING_AGG(kprag.api_group, ', ') AS apiGroups,  " +
//            "    STRING_AGG(kprr.resource, ', ') AS resources,  " +
//            "    STRING_AGG(kprrn.resource_name, ', ') AS resourceNames  " +
//            "FROM kubernetes_role  kr   " +
//            "INNER JOIN kubernetes_policy_rule kpr ON kr.id = kpr.cluster_role_id   " +
//            "INNER JOIN kubernetes_policy_rule_verbs kprv ON kprv.policy_rule_id = kpr.id   " +
//            "INNER JOIN kubernetes_policy_rule_api_groups kprag ON kprag.policy_rule_id = kpr.id   " +
//            "INNER JOIN kubernetes_policy_rule_resources kprr ON kprr.policy_rule_id = kpr.id   " +
//            "INNER JOIN kubernetes_policy_rule_resource_names kprrn ON kprrn.policy_rule_id = kpr.id   " +
//            "WHERE kr.ws_tenant_name = :wsTenantName AND kr.uid = :roleUID AND kr.cloud_provider_type = :cloudTYpe" +
//            "GROUP BY kr.id, kr.uid, kr.\"name\", kr.\"namespace\", kr.cloud_provider_type, kr.cluster_id"
//            , nativeQuery = true)
//    Optional<K8RoleProjection> getK8RoleUsingUidAndWsTenantNameAndCloudType(String wsTenantName, String cloudTYpe, String roleUID);


//    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(cr.id, cr.uid, cr.name, cr.namespace, cr.clusterId, cr.cloudProviderType, cr.resourceAccountId) " +
//            "FROM K8ClusterRole cr WHERE cr.wsTenantName = :wsTenantName and cr.cloudProviderType = :cloudProviderType")
//    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudType(String wsTenantName, CloudProviderType cloudProviderType);
//
//
//    @Query(value = "SELECT  " +
//            "    kcr.id, kcr.uid, kcr.\"name\", kcr.\"namespace\", kcr.cloud_provider_type AS CloudType, kcr.cluster_id AS ClusterId,  " +
//            "    kcr.resource_account_id AS resourceAccountId, " +
//            "    STRING_AGG(kprv.verb, ', ') AS verbs, " +
//            "    STRING_AGG(kprag.api_group, ', ') AS apiGroups, " +
//            "    STRING_AGG(kprr.resource, ', ') AS resources, " +
//            "    STRING_AGG(kprrn.resource_name, ', ') AS resourceNames " +
//            "FROM kubernetes_cluster_role kcr  " +
//            "INNER JOIN kubernetes_policy_rule kpr ON kcr.id = kpr.cluster_role_id  " +
//            "INNER JOIN kubernetes_policy_rule_verbs kprv ON kprv.policy_rule_id = kpr.id  " +
//            "INNER JOIN kubernetes_policy_rule_api_groups kprag ON kprag.policy_rule_id = kpr.id  " +
//            "INNER JOIN kubernetes_policy_rule_resources kprr ON kprr.policy_rule_id = kpr.id  " +
//            "INNER JOIN kubernetes_policy_rule_resource_names kprrn ON kprrn.policy_rule_id = kpr.id  " +
//            "WHERE kcr.ws_tenant_name = :wsTenantName AND kcr.uid = :roleUID  " +
//            "GROUP BY kcr.id, kcr.uid, kcr.\"name\", kcr.\"namespace\", kcr.cloud_provider_type, kcr.cluster_id", nativeQuery = true)
//    Optional<K8RoleProjection> getK8ClusterRoleUsingUidAndTenantNameAndCloudType(String wsTenantName, String roleUID);
}
