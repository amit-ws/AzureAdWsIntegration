package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8ServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8ServiceAccountRepository extends JpaRepository<K8ServiceAccount, Long> {
    List<K8ServiceAccount> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

    List<K8ServiceAccount> findAllByWsTenantNameAndCloudProviderTypeAndClusterIdAndNamespaceOrderByName(String wsTenantName, CloudProviderType cloudProviderType,
                                                                                                        String clusterId, String namespace);
    List<K8ServiceAccount> findAllByClusterIdAndWsTenantName(String clusterId, String wsTenantName);
    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);

}
