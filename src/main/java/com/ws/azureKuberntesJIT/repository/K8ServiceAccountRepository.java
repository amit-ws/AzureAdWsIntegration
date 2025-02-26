package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Deployment;
import com.ws.azureKuberntesJIT.enttity.K8ServiceAccount;
import com.ws.azureKuberntesJIT.models.K8ServiceAccountDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8ServiceAccountRepository extends JpaRepository<K8ServiceAccount, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ServiceAccountDTO(ksa.id, ksa.apiVersion, ksa.selfLink, ksa.kind, ksa.resourceVersion, ksa.generation, " +
            "    ksa.name, ksa.uid, ksa.generateName, ksa.creationTimestamp, ksa.deletionTimestamp, ksa.syncedAt, ksa.updatedAt, ksa.clusterId, " +
            "    ksa.namespace, ksa.cloudProviderType, ksa.wsTenantName, ksa.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8ServiceAccount ksa " +
            "LEFT JOIN PublishedResource pr ON ksa.uid = pr.resourceId " +
            "WHERE ksa.wsTenantName = :wsTenantName AND ksa.clusterId = :clusterId AND ksa.cloudProviderType = :cloudProviderType AND ksa.namespace = :namespace " +
            "ORDER BY ksa.name")
    List<K8ServiceAccountDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                              String clusterId, String namespace);

    List<K8ServiceAccount> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                        String clusterId, String namespace);

    List<K8ServiceAccount> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ServiceAccountDTO(ksa.id, ksa.uid, ksa.name, ksa.clusterId, ksa.cloudResourceAccountId, ksa.wsTenantName, ksa.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8ServiceAccount ksa " +
            "INNER JOIN PublishedResource pr ON ksa.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName " +
            "ORDER BY ksa.name")
    List<K8ServiceAccountDTO> findAllPublishedK8ServiceAccountsByWsTenantName(String wsTenantName);

}
