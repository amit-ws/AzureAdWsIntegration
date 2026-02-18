package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8RoleReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8RoleReferenceRepository extends JpaRepository<K8RoleReference, Long> {

    @Query("SELECT krr FROM K8RoleReference krr WHERE krr.wsTenantName = :wsTenantName AND krr.cloudProviderType = :cloudType AND (:cloudIds IS NULL OR krr.cloudResourceAccountId IN :cloudIds) ")
    List<K8RoleReference> findAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

//    @Modifying
//    @Query("DELETE FROM K8RoleReference WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
//    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);
}
