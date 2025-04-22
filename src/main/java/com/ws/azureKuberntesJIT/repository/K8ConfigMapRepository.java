package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8ConfigMap;
import com.ws.azureKuberntesJIT.enttity.K8Secret;
import com.ws.azureKuberntesJIT.models.K8ConfigMapDTO;
import com.ws.azureKuberntesJIT.models.K8DaemonSetDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8ConfigMapRepository extends JpaRepository<K8ConfigMap, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ConfigMapDTO(kcm.id, kcm.apiVersion, kcm.selfLink, kcm.kind, kcm.resourceVersion, kcm.generation, \n" +
            "    kcm.name, kcm.uid, kcm.generateName, kcm.creationTimestamp, kcm.deletionTimestamp, kcm.syncedAt, kcm.updatedAt, kcm.clusterId, \n" +
            "    kcm.namespace, kcm.cloudProviderType, kcm.wsTenantName, kcm.cloudResourceAccountId, kcm.immutable, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END)  " +
            "FROM K8ConfigMap kcm " +
            "LEFT JOIN PublishedResource pr ON kcm.uid = pr.resourceId " +
            "WHERE kcm.wsTenantName = :wsTenantName AND kcm.clusterId = :clusterId AND kcm.cloudProviderType = :cloudProviderType AND kcm.namespace = :namespace " +
            "ORDER BY kcm.name")
    List<K8ConfigMapDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                         String clusterId, String namespace);

    List<K8ConfigMap> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                   String clusterId, String namespace);

    List<K8ConfigMap> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Modifying
    @Query("DELETE FROM K8ConfigMap WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ConfigMapDTO(kcm.id, kcm.uid, kcm.name, kcm.clusterId, kcm.cloudResourceAccountId, kcm.wsTenantName, kcm.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8ConfigMap kcm " +
            "INNER JOIN PublishedResource pr ON kcm.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND kcm.clusterId = :clusterId " +
            "ORDER BY kcm.name")
    List<K8ConfigMapDTO> findAllPublishedK8ConfigMapsByWsTenantName(String wsTenantName, String clusterId);


}