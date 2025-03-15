package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Deployment;
import com.ws.azureKuberntesJIT.enttity.K8Node;
import com.ws.azureKuberntesJIT.models.K8ConfigMapDTO;
import com.ws.azureKuberntesJIT.models.K8DeploymentDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8DeploymentRepository extends JpaRepository<K8Deployment, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8DeploymentDTO(kd.id, kd.apiVersion, kd.selfLink, kd.kind, kd.resourceVersion, kd.generation, " +
            "    kd.name, kd.uid, kd.generateName, kd.creationTimestamp, kd.deletionTimestamp, kd.syncedAt, kd.updatedAt, kd.clusterId, \n" +
            "    kd.namespace, kd.cloudProviderType, kd.wsTenantName, kd.cloudResourceAccountId, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END)  " +
            "FROM K8Deployment kd " +
            "LEFT JOIN PublishedResource pr ON kd.uid = pr.resourceId " +
            "WHERE kd.wsTenantName = :wsTenantName AND kd.clusterId = :clusterId AND kd.cloudProviderType = :cloudProviderType AND kd.namespace = :namespace " +
            "ORDER BY kd.name")
    List<K8DeploymentDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                         String clusterId, String namespace);
    List<K8Deployment> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                         String clusterId, String namespace);

    List<K8Deployment> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8DeploymentDTO(kd.id, kd.uid, kd.name, kd.clusterId, kd.cloudResourceAccountId, kd.wsTenantName, kd.cloudProviderType, " +
            "CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Deployment kd " +
            "INNER JOIN PublishedResource pr ON kd.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName " +
            "ORDER BY kd.name")
    List<K8DeploymentDTO> findAllPublishedK8DeploymentsByWsTenantName(String wsTenantName);


}
