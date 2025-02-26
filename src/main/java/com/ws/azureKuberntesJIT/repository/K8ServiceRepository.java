package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Ingress;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.enttity.K8Service;
import com.ws.azureKuberntesJIT.models.K8ServiceDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8ServiceRepository extends JpaRepository<K8Service, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ServiceDTO(ks.id, ks.apiVersion, ks.selfLink, ks.kind, ks.resourceVersion, ks.generation, " +
            "    ks.name, ks.uid, ks.generateName, ks.creationTimestamp, ks.deletionTimestamp, ks.syncedAt, ks.updatedAt, ks.clusterId, " +
            "    ks.namespace, ks.cloudProviderType, ks.wsTenantName, ks.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Service ks " +
            "LEFT JOIN PublishedResource pr ON ks.uid = pr.resourceId " +
            "WHERE ks.wsTenantName = :wsTenantName AND ks.clusterId = :clusterId AND ks.cloudProviderType = :cloudProviderType AND ks.namespace = :namespace " +
            "ORDER BY ks.name")
    List<K8ServiceDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                       String clusterId, String namespace);

    List<K8Service> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                 String clusterId, String namespace);

    List<K8Service> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ServiceDTO(ks.id, ks.uid, ks.name, ks.clusterId, ks.cloudResourceAccountId, ks.wsTenantName, ks.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Service ks " +
            "INNER JOIN PublishedResource pr ON ks.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName " +
            "ORDER BY ks.name")
    List<K8ServiceDTO> findAllPublishedK8ServicesByWsTenantName(String wsTenantName);


}
