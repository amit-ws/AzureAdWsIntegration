package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8NetworkPolicy;
import com.ws.azureKuberntesJIT.enttity.K8Secret;
import com.ws.azureKuberntesJIT.models.K8NetworkPolicyDTO;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8NetworkPolicyRepository extends JpaRepository<K8NetworkPolicy, Long> {
    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8NetworkPolicyDTO(knp.id, knp.apiVersion, knp.selfLink, knp.kind, knp.resourceVersion, knp.generation, " +
            "    knp.name, knp.uid, knp.generateName, knp.creationTimestamp, knp.deletionTimestamp, knp.syncedAt, knp.updatedAt, knp.clusterId, " +
            "    knp.namespace, knp.cloudProviderType, knp.wsTenantName, knp.cloudResourceAccountId, " +
            "    CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8NetworkPolicy knp " +
            "LEFT JOIN PublishedResource pr ON knp.uid = pr.resourceId " +
            "WHERE knp.wsTenantName = :wsTenantName AND knp.clusterId = :clusterId AND knp.cloudProviderType = :cloudProviderType AND knp.namespace = :namespace " +
            "ORDER BY knp.name")
    List<K8NetworkPolicyDTO> findAllUsingWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                             String clusterId, String namespace);

    List<K8NetworkPolicy> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                       String clusterId, String namespace);

    List<K8NetworkPolicy> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);

    @Modifying
    @Query("DELETE FROM K8NetworkPolicy WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

    @Query("SELECT new com.ws.azureKuberntesJIT.models.K8NetworkPolicyDTO(knp.id, knp.uid, knp.name, knp.clusterId, knp.cloudResourceAccountId, knp.wsTenantName, knp.cloudProviderType, CASE WHEN pr.resourceId IS NULL THEN FALSE ELSE TRUE END) " +
            "FROM K8NetworkPolicy knp " +
            "INNER JOIN PublishedResource pr ON knp.uid = pr.resourceId " +
            "WHERE pr.wsTenantName = :wsTenantName AND knp.clusterId = :clusterId " +
            "ORDER BY knp.name")
    List<K8NetworkPolicyDTO> findAllPublishedK8NetworkPoliciesByWsTenantName(String wsTenantName, String clusterId);

}
