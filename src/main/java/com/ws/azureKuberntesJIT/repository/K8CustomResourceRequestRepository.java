package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8CustomResourceRequest;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface K8CustomResourceRequestRepository extends JpaRepository<K8CustomResourceRequest, UUID> {
    List<K8CustomResourceRequest> findAllByStatus(RequestStatus status);

    @Query(value = "SELECT kcrr " +
            "FROM kubernetes_custom_resource_request kcrr " +
            "WHERE kcrr.ws_tenant_name = :wsTenantName AND kcrr.cloud_resource_account_id = :cloudId AND kcrr.cluster_id = :clusterId AND kcrr.role_id = :roleId " +
            "AND kcrr.cloud_type = :cloudType AND (:namespace IS NULL OR kcrr.\"namespace\" = :namespace) AND kcrr.user_name = :userName " +
            "AND kcrr.status NOT IN (:statuses) "
            , nativeQuery = true)
    Optional<K8CustomResourceRequest> getCustomRequestWithParamsAndStatusNotIn(String wsTenantName, String cloudType, String cloudId, String clusterId,
                                                                               String namespace, String roleId, String userName, String[] statuses);


    @Query(value = "SELECT kcrr.* " +
            "FROM kubernetes_custom_resource_request kcrr " +
            "INNER JOIN azure_user au ON kcrr.user_name = au.user_principal_name " +
            "WHERE kcrr.ws_tenant_name = :wsTenantName " +
            "  AND kcrr.cloud_type = :cloudType " +
            "  AND (:status IS NULL OR kcrr.status = :status) " +
            "  AND (:userName IS NULL OR kcrr.user_name = :userName) " +
            "ORDER BY kcrr.requested_at DESC"
            , nativeQuery = true)
    List<K8CustomResourceRequest> getK8CustomResourceRequestWithParams(String wsTenantName, String cloudType, String status, String userName);


}
