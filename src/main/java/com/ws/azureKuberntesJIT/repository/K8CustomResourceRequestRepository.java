package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8CustomResourceRequest;
import com.ws.azureKuberntesJIT.models.K8CustomResourceRequestDTO;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface K8CustomResourceRequestRepository extends JpaRepository<K8CustomResourceRequest, UUID> {
    List<K8CustomResourceRequest> findAllByStatus(RequestStatus status);

    @Query(value = "SELECT kcrr.* " +
            "FROM kubernetes_custom_resource_request kcrr " +
            "WHERE kcrr.ws_tenant_name = :wsTenantName AND kcrr.cloud_resource_account_id = :cloudId AND kcrr.cluster_id = :clusterId AND kcrr.role_id = :roleId " +
            "AND kcrr.cloud_type = :cloudType AND (:namespace IS NULL OR kcrr.\"namespace\" = :namespace) AND kcrr.user_name = :userName " +
            "AND kcrr.status NOT IN (:statuses) "
            , nativeQuery = true)
    Optional<K8CustomResourceRequest> findCustomRequestWithParamsAndStatusNotIn(String wsTenantName, String cloudType, String cloudId, String clusterId,
                                                                                String namespace, String roleId, String userName, String[] statuses);


    @Query(value = "SELECT kcrr.* " +
            "FROM kubernetes_custom_resource_request kcrr " +
            "INNER JOIN azure_user au ON kcrr.user_name = au.user_principal_name " +
            "WHERE kcrr.ws_tenant_name = :wsTenantName " +
            "  AND kcrr.cloud_type = :cloudType " +
            "  AND (:status IS NULL OR kcrr.status = :status) " +
            "  AND (:userName IS NULL OR kcrr.user_name = :userName) " +
            "  AND (:cloudResourceAccountId IS NULL OR kcrr.cloud_resource_account_id = :cloudResourceAccountId) " +
            "ORDER BY kcrr.requested_at DESC"
            , nativeQuery = true)
    List<K8CustomResourceRequest> getK8CustomResourceRequestWithParams(String wsTenantName, String cloudType, String status,
                                                                       String userName, String cloudResourceAccountId);


    @Query(" SELECT new com.ws.azureKuberntesJIT.models.K8CustomResourceRequestDTO(kcrr.id, kcrr.roleRequest.roleId, kcrr.roleRequest.roleName, kcrr.roleRequest.roleKind, kcrr.roleRequest.verbs, kcrr.roleRequest.policyResourceName, kcrr.roleRequest.isRoleCustomCreated, " +
            "   kcrr.roleBindRequest.roleBindingName, kcrr.roleBindRequest.k8ResourceName, kcrr.roleBindRequest.resourceType, kcrr.roleBindRequest.bindingType, kcrr.roleBindRequest.subjectKind, kcrr.roleBindRequest.userName, kcrr.roleBindRequest.namespace, kcrr.roleBindRequest.level, " +
            "   kcrr.status, kcrr.requestedAt, kcrr.expiryTimeAmount, kcrr.validFrom, kcrr.validTo, kcrr.wsUserEmail, kcrr.message, kcrr.clusterId, kcrr.cloudResourceAccountId, kcrr.cloudType, kcrr.wsTenantName, kcrr.clusterName, kcrr.roleBindRequest.userDisplayName) " +
            " FROM K8CustomResourceRequest kcrr " +
            " WHERE kcrr.wsTenantName = :wsTenantName " +
            "   AND kcrr.cloudType = :cloudType " +
            "   AND (:status IS NULL OR kcrr.status = :status) " +
            "   AND (:userName IS NULL OR kcrr.roleBindRequest.userName = :userName) " +
            "   AND (:cloudResourceAccountId IS NULL OR kcrr.cloudResourceAccountId = :cloudResourceAccountId) " +
            " ORDER BY kcrr.requestedAt DESC")
    List<K8CustomResourceRequestDTO> findK8CustomResourceRequestWithParams(String wsTenantName, CloudProviderType cloudType, RequestStatus status,
                                                                           String userName, String cloudResourceAccountId);


    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8CustomResourceRequestDTO(kcrr.id, kcrr.clusterId, kcrr.roleRequest.roleId, kcrr.roleRequest.roleName, kcrr.roleRequest.isRoleCustomCreated, " +
            "kcrr.roleBindRequest.namespace, kcrr.roleBindRequest.roleBindingName) " +
            "FROM K8CustomResourceRequest kcrr " +
            "WHERE kcrr.wsTenantName = :wsTenantName AND kcrr.cloudType = :cloudType AND kcrr.status = :status AND (:cloudIDs IS NULL OR kcrr.cloudResourceAccountId IN :cloudIDs) ")
    List<K8CustomResourceRequestDTO> findK8CustomResourceRequestUsingWsTenantAndCloudIDs(String wsTenantName, CloudProviderType cloudType,
                                                                                         Collection<String> cloudIDs, RequestStatus status);

    @Modifying
    @Query("DELETE FROM K8CustomResourceRequest kcrr WHERE kcrr.wsTenantName = :wsTenantName AND kcrr.cloudType = :cloudType AND (:cloudIDs IS NULL OR kcrr.cloudResourceAccountId IN :cloudIDs)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudResourceAccountIdIn(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIDs);

}
