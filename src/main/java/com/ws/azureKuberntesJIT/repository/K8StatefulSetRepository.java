package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.enttity.K8ReplicaSet;
import com.ws.azureKuberntesJIT.enttity.K8StatefulSet;
import com.ws.azureKuberntesJIT.models.K8StatefulSetDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8StatefulSetRepository extends JpaRepository<K8StatefulSet, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8StatefulSetDTO(kss.id, kss.apiVersion, kss.selfLink, kss.kind, kss.resourceVersion, kss.generation, " +
            "    kss.name, kss.uid, kss.generateName, kss.creationTimestamp, kss.deletionTimestamp, kss.syncedAt, kss.updatedAt, kss.clusterId, " +
            "    kss.namespace, kss.cloudProviderType, kss.wsTenantName, kss.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8StatefulSet kss " +
            "LEFT JOIN PublishedResource pr ON kss.uid = pr.resourceId " +
            "WHERE kss.wsTenantName = :wsTenantName AND kss.clusterId = :clusterId AND kss.cloudProviderType = :cloudProviderType AND kss.namespace = :namespace " +
            "ORDER BY kss.name")
    List<K8StatefulSetDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                           String clusterId, String namespace);

    List<K8StatefulSet> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                             String clusterId, String namespace);
    List<K8StatefulSet> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8StatefulSetDTO(kss.id, kss.uid, kss.name, kss.clusterId, kss.cloudResourceAccountId, kss.wsTenantName, kss.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8StatefulSet kss " +
            "INNER JOIN PublishedResource pr ON kss.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName " +
            "ORDER BY kss.name")
    List<K8StatefulSetDTO> findAllPublishedK8StatefulSetsByWsTenantName(String wsTenantName);


}
