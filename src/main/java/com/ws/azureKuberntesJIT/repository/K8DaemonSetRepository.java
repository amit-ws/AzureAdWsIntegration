package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8DaemonSet;
import com.ws.azureKuberntesJIT.enttity.K8Job;
import com.ws.azureKuberntesJIT.enttity.K8StatefulSet;
import com.ws.azureKuberntesJIT.models.K8DaemonSetDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8DaemonSetRepository extends JpaRepository<K8DaemonSet, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8DaemonSetDTO(kd.id, kd.apiVersion, kd.selfLink, kd.kind, kd.resourceVersion, kd.generation, " +
            "    kd.name, kd.uid, kd.generateName, kd.creationTimestamp, kd.deletionTimestamp, kd.syncedAt, kd.updatedAt, kd.clusterId, " +
            "    kd.namespace, kd.cloudProviderType, kd.wsTenantName, kd.cloudResourceAccountId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END)  " +
            "FROM K8DaemonSet kd " +
            "LEFT JOIN PublishedResource pr ON kd.uid = pr.resourceId " +
            "WHERE kd.wsTenantName = :wsTenantName AND kd.clusterId = :clusterId AND kd.cloudProviderType = :cloudProviderType AND kd.namespace = :namespace " +
            "ORDER BY kd.name")
    List<K8DaemonSetDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                         String clusterId, String namespace);

    List<K8DaemonSet> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                   String clusterId, String namespace);

    List<K8DaemonSet> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8DaemonSetDTO(kds.id, kds.uid, kds.name, kds.clusterId, kds.cloudResourceAccountId, kds.wsTenantName, kds.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8DaemonSet kds " +
            "INNER JOIN PublishedResource pr ON kds.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND kds.clusterId = :clusterId " +
            "ORDER BY kds.name")
    List<K8DaemonSetDTO> findAllPublishedK8DaemonSetsByWsTenantName(String wsTenantName, String clusterId);


}
