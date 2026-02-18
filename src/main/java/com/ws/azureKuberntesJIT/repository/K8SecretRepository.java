package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Secret;
import com.ws.azureKuberntesJIT.enttity.K8ServiceAccount;
import com.ws.azureKuberntesJIT.models.K8SecretDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8SecretRepository extends JpaRepository<K8Secret, Long> {

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8SecretDTO(ks.id, ks.apiVersion, ks.selfLink, ks.kind, ks.resourceVersion, ks.generation, " +
            "    ks.name, ks.uid, ks.generateName, ks.creationTimestamp, ks.deletionTimestamp, ks.syncedAt, ks.updatedAt, ks.clusterId, " +
            "    ks.namespace, ks.cloudProviderType, ks.wsTenantName, ks.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Secret ks " +
            "LEFT JOIN PublishedResource pr ON ks.uid = pr.resourceId " +
            "WHERE ks.wsTenantName = :wsTenantName AND ks.clusterId = :clusterId AND ks.cloudProviderType = :cloudProviderType AND ks.namespace = :namespace " +
            "ORDER BY ks.name")
    List<K8SecretDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                      String clusterId, String namespace);

    List<K8Secret> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                String clusterId, String namespace);

    List<K8Secret> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Modifying
    @Query("DELETE FROM K8Secret WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8SecretDTO(ks.id, ks.uid, ks.name, ks.clusterId, ks.cloudResourceAccountId, ks.wsTenantName, ks.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8Secret ks " +
            "INNER JOIN PublishedResource pr ON ks.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND ks.clusterId = :clusterId " +
            "ORDER BY ks.name")
    List<K8SecretDTO> findAllPublishedK8SecretsByWsTenantName(String wsTenantName, String clusterId);

}
