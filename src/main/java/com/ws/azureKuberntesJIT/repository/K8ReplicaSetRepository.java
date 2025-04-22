package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8CronJob;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.enttity.K8NetworkPolicy;
import com.ws.azureKuberntesJIT.enttity.K8ReplicaSet;
import com.ws.azureKuberntesJIT.models.K8ReplicaSetDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8ReplicaSetRepository extends JpaRepository<K8ReplicaSet, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ReplicaSetDTO(krs.id, krs.apiVersion, krs.selfLink, krs.kind, krs.resourceVersion, krs.generation, " +
            "    krs.name, krs.uid, krs.generateName, krs.creationTimestamp, krs.deletionTimestamp, krs.syncedAt, krs.updatedAt, krs.clusterId, " +
            "    krs.namespace, krs.cloudProviderType, krs.wsTenantName, krs.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8ReplicaSet krs " +
            "LEFT JOIN PublishedResource pr ON krs.uid = pr.resourceId " +
            "WHERE krs.wsTenantName = :wsTenantName AND krs.clusterId = :clusterId AND krs.cloudProviderType = :cloudProviderType AND krs.namespace = :namespace " +
            "ORDER BY krs.name")
    List<K8ReplicaSetDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                          String clusterId, String namespace);

    List<K8ReplicaSet> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                    String clusterId, String namespace);

    List<K8ReplicaSet> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8ReplicaSetDTO(krs.id, krs.uid, krs.name, krs.clusterId, krs.cloudResourceAccountId, krs.wsTenantName, krs.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8ReplicaSet krs " +
            "INNER JOIN PublishedResource pr ON krs.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND krs.clusterId = :clusterId " +
            "ORDER BY krs.name")
    List<K8ReplicaSetDTO> findAllPublishedK8ReplicaSetsByWsTenantName(String wsTenantName, String clusterId);

    @Modifying
    @Query("DELETE FROM K8ReplicaSet WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

}
