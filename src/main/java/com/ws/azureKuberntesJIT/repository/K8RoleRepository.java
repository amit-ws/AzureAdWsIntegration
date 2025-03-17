package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.enttity.K8Role;
import com.ws.azureKuberntesJIT.response.K8RoleResponse;
import com.ws.azureKuberntesJIT.response.RoleResponseProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8RoleRepository extends JpaRepository<K8Role, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(kr.id, kr.uid, kr.name, kr.namespace, kr.roleLevel, kr.clusterId, kr.cloudProviderType, " +
            "kr.cloudResourceAccountId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Role kr " +
            "LEFT JOIN PublishedResource pr ON kr.uid = pr.resourceId " +
            "WHERE kr.wsTenantName = :wsTenantName AND kr.cloudProviderType = :cloudProviderType AND (:roleType IS NULL OR kr.roleLevel = :roleType) " +
            "ORDER BY kr.name")
    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudTypeAndRoleType(String wsTenantName, CloudProviderType cloudProviderType, K8ResourceLevel roleType);


    @Query("SELECT new com.ws.azureKuberntesJIT.response.K8RoleResponse(kr.id, kr.uid, kr.name, kr.namespace, kr.roleLevel, kr.clusterId, kr.cloudProviderType, " +
            "kr.cloudResourceAccountId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Role kr " +
            "LEFT JOIN PublishedResource pr ON kr.uid = pr.resourceId " +
            "WHERE kr.wsTenantName = :wsTenantName AND kr.clusterId = :clusterId AND kr.cloudProviderType = :cloudProviderType AND (:roleType IS NULL OR kr.roleLevel = :roleType) " +
            "ORDER BY kr.name")
    List<K8RoleResponse> findAllRolesUsingWsTenantNameAndCloudTypeAndRoleTypeAndClusterId(String wsTenantName, String clusterId, CloudProviderType cloudProviderType, K8ResourceLevel roleType);


    @Query(value = "select " +
            "distinct kr.uid as roleId , kr.\"name\" as roleName , kr.role_type as roleType " +
            "from kubernetes_role kr inner join kubernetes_policy_rule kpr on kr.uid = kpr.roleuid " +
            "inner join kubernetes_policy_rule_resources kprr on kpr.id = kprr.policy_rule_id  where kr.ws_tenant_name = :wsTenantName and kr.cluster_id = :clusterId " +
            "and kr.cloud_resource_account_id = :resourceId and kprr.resource = :resourceType and kr.cloud_provider_type = :cloudType order by kr.\"name\""
            , nativeQuery = true)
    List<RoleResponseProjection> findApplicableRoles(String wsTenantName, String resourceType, String resourceId, String clusterId, String cloudType);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);


    @Query(value =
            "SELECT kr.uid as roleUid, kr.\"name\" as roleName, kr.role_level as roleLevel, kr.role_kind as roleKind " +
                    "FROM kubernetes_role kr  " +
                    "INNER JOIN kubernetes_policy_rule kpr ON kr.uid = kpr.roleuid  " +
                    "INNER JOIN kubernetes_policy_rule_resource_names kprrn ON kpr.id = kprrn.policy_rule_id  " +
                    "INNER JOIN published_resource pr ON pr.resource_id = kr.uid  " +
                    "WHERE kr.ws_tenant_name = :wsTenantName AND kr.cloud_resource_account_id = :resourceAccountId AND kr.cluster_id = :clusterId " +
                    "AND kr.cloud_provider_type = :cloudType AND kprrn.resource_name = :resourceName AND pr.resource_type = :publishResourceType " +
                    "AND (:namespace IS NULL OR kr.\"namespace\" = :namespace) " +
                    "ORDER BY kr.\"name\"",
            nativeQuery = true)
    List<RoleResponseProjection> suggestRoles(String wsTenantName, String cloudType, String resourceAccountId, String clusterId, String namespace,
                                              String resourceName, String publishResourceType);

    @Modifying
    void deleteByUid(String uid);



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
