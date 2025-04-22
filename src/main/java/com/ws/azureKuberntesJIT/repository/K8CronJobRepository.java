package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8CronJob;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.models.K8CronJobDTO;
import com.ws.azureKuberntesJIT.models.K8DaemonSetDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8CronJobRepository extends JpaRepository<K8CronJob, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8CronJobDTO(kcj.id, kcj.apiVersion, kcj.selfLink, kcj.kind, kcj.resourceVersion, kcj.generation, " +
            "    kcj.name, kcj.uid, kcj.generateName, kcj.creationTimestamp, kcj.deletionTimestamp, kcj.syncedAt, kcj.updatedAt, kcj.clusterId, " +
            "    kcj.namespace, kcj.cloudProviderType, kcj.wsTenantName, kcj.cloudResourceAccountId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END)  " +
            "FROM K8ConfigMap kcj " +
            "LEFT JOIN PublishedResource pr ON kcj.uid = pr.resourceId " +
            "WHERE kcj.wsTenantName = :wsTenantName AND kcj.clusterId = :clusterId AND kcj.cloudProviderType = :cloudProviderType AND kcj.namespace = :namespace " +
            "ORDER BY kcj.name")
    List<K8CronJobDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                       String clusterId, String namespace);

    List<K8CronJob> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                             String clusterId, String namespace);
    List<K8CronJob> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8CronJobDTO(kcj.id, kcj.uid, kcj.name, kcj.clusterId, kcj.cloudResourceAccountId, kcj.wsTenantName, kcj.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8CronJob kcj " +
            "INNER JOIN PublishedResource pr ON kcj.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND kcj.clusterId = :clusterId " +
            "ORDER BY kcj.name")
    List<K8CronJobDTO> findAllPublishedK8CronJobsByWsTenantName(String wsTenantName, String clusterId);


    @Modifying
    @Query("DELETE FROM K8CronJob WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);


}
