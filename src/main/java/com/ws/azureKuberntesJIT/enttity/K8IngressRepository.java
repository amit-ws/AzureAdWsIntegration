package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.models.K8ConfigMapDTO;
import com.ws.azureKuberntesJIT.models.K8IngressDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8IngressRepository extends JpaRepository<K8Ingress, Long> {

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8IngressDTO(ki.id, ki.apiVersion, ki.selfLink, ki.kind, ki.resourceVersion, ki.generation, " +
            "    ki.name, ki.uid, ki.generateName, ki.creationTimestamp, ki.deletionTimestamp, ki.syncedAt, ki.updatedAt, ki.clusterId, " +
            "    ki.namespace, ki.cloudProviderType, ki.wsTenantName, ki.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Ingress ki " +
            "LEFT JOIN PublishedResource pr ON ki.uid = pr.resourceId " +
            "WHERE ki.wsTenantName = :wsTenantName AND ki.clusterId = :clusterId AND ki.cloudProviderType = :cloudProviderType AND ki.namespace = :namespace " +
            "ORDER BY ki.name")
    List<K8IngressDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                         String clusterId, String namespace);

    List<K8Ingress> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                 String clusterId, String namespace);

    List<K8Ingress> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8IngressDTO(ki.id, ki.uid, ki.name, ki.clusterId, ki.cloudResourceAccountId, ki.wsTenantName, ki.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Ingress ki " +
            "INNER JOIN PublishedResource pr ON ki.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName " +
            "ORDER BY ki.name")
    List<K8IngressDTO> findAllPublishedK8IngressesByWsTenantName(String wsTenantName);


}
