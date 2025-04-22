package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8CronJob;
import com.ws.azureKuberntesJIT.enttity.K8DaemonSet;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.enttity.K8NetworkPolicy;
import com.ws.azureKuberntesJIT.models.K8JobDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8JobRepository extends JpaRepository<K8Job, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8JobDTO(kj.id, kj.apiVersion, kj.selfLink, kj.kind, kj.resourceVersion, kj.generation, " +
            "    kj.name, kj.uid, kj.generateName, kj.creationTimestamp, kj.deletionTimestamp, kj.syncedAt, kj.updatedAt, kj.clusterId, " +
            "    kj.namespace, kj.cloudProviderType, kj.wsTenantName, kj.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Job kj " +
            "LEFT JOIN PublishedResource pr ON kj.uid = pr.resourceId " +
            "WHERE kj.wsTenantName = :wsTenantName AND kj.clusterId = :clusterId AND kj.cloudProviderType = :cloudProviderType AND kj.namespace = :namespace " +
            "ORDER BY kj.name")
    List<K8JobDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                   String clusterId, String namespace);

    List<K8Job> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                             String clusterId, String namespace);

    List<K8Job> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8JobDTO(kj.id, kj.uid, kj.name, kj.clusterId, kj.cloudResourceAccountId, kj.wsTenantName, kj.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Job kj " +
            "INNER JOIN PublishedResource pr ON kj.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND kj.clusterId = :clusterId " +
            "ORDER BY kj.name")
    List<K8JobDTO> findAllPublishedK8JobsByWsTenantName(String wsTenantName, String clusterId);

    @Modifying
    @Query("DELETE FROM K8Job WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);


}
