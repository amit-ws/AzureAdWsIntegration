package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Role;
import com.ws.azureKuberntesJIT.enttity.K8RoleBind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface K8RoleBindRepository extends JpaRepository<K8RoleBind, Long> {

    @Query("SELECT krb FROM K8RoleBind krb WHERE krb.wsTenantName = :wsTenantName AND krb.cloudProviderType = :cloudType AND (:cloudIds IS NULL OR krb.cloudResourceAccountId IN :cloudIds)")
    List<K8RoleBind> findAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);


//    @Modifying
//    @Query("DELETE FROM K8RoleBind WHERE wsTenantName = :wsTenantName AND cloudProviderType = :cloudType AND (:cloudIds IS NULL OR cloudResourceAccountId IN :cloudIds)")
//    void deleteAllUsingWsTenantNameAndCloudTypeAndCloudIds(String wsTenantName, CloudProviderType cloudType, Collection<String> cloudIds);

    @Modifying
    void deleteByUid(String uid);
}
