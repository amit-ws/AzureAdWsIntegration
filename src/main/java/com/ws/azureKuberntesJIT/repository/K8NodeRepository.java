package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Node;
import com.ws.azureKuberntesJIT.models.K8DaemonSetDTO;
//import com.ws.azureKuberntesJIT.models.K8NodeDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8NodeRepository extends JpaRepository<K8Node, Long> {
//    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8NodeDTO(kn.id, kn.apiVersion, kn.selfLink, kn.kind, kn.resourceVersion, kn.generation, " +
//            "    kn.name, kn.uid, kn.generateName, kn.creationTimestamp, kn.deletionTimestamp, kn.syncedAt, kn.updatedAt, kn.clusterId, " +
//            "    kn.namespace, kn.cloudProviderType, kn.wsTenantName, kn.cloudResourceAccountId, kn.phase, kn.externalID, kn.podCIDR, " +
//            "    kn.unschedulable, kn.providerID, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END)  " +
//            "FROM K8Node kn " +
//            "LEFT JOIN PublishedResource pr ON kn.uid = pr.resourceId " +
//            "WHERE kn.wsTenantName = :wsTenantName AND kn.clusterId = :clusterId AND kn.cloudProviderType = :cloudProviderType AND kn.namespace = :namespace " +
//            "ORDER BY kn.name")
//    List<K8NodeDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
//                                                                                                    String clusterId, String namespace);

    List<K8Node> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdOrderByName(String wsTenantName, CloudProviderType cloudProviderType, String clusterId);

    List<K8Node> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    @Query("DELETE FROM K8Node WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);


    @Query("SELECT kn FROM K8Node kn INNER JOIN PublishedResource pr ON kn.uid = pr.resourceId WHERE pr.wsTenantName = :wsTenantName ORDER BY kn.name")
    List<K8Node> findAllPublishedK8Nodes(String wsTenantName);

}
