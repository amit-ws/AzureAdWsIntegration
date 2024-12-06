package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureUserCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AzureUserCredentialRepository extends JpaRepository<AzureUserCredential, Integer> {
    void deleteByTenantId(String tenantId);
    Optional<AzureUserCredential> findByWsTenantName(String wsTenantName);

    @Query(value = "SELECT auc FROM azure_user_credential auc INNER JOIN azure_user au ON auc.ws_tenant_name = au.ws_tenant_name \n" +
            "WHERE au.azure_id = :azureUserId", nativeQuery = true)
    Optional<AzureUserCredential> findAzureUserCredentialUsingAzureUserId(String azureUserId);
}
