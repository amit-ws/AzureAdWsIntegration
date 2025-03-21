package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.dto.AzureAuthenticationCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AzureUserCredentialRepository extends JpaRepository<AzureUserCredential, Integer> {
    void deleteByTenantId(String tenantId);

    void deleteByWsTenantName(String wsTenantName);

    Optional<AzureUserCredential> findByWsTenantName(String wsTenantName);

    @Query(value = "SELECT new com.ws.azureAdIntegration.dto.AzureAuthenticationCredentialDTO(auc.tenantId, auc.clientId, auc.clientSecret) " +
            "FROM AzureUserCredential auc WHERE auc.wsTenantName = :wsTenantName")
    Optional<AzureAuthenticationCredentialDTO> findAzureUserCredentialUsingWsTenantName(String wsTenantName);

    @Query(value = "SELECT auc FROM azure_user_credential auc INNER JOIN azure_user au ON auc.ws_tenant_name = au.ws_tenant_name \n" +
            "WHERE au.azure_id = :azureUserId", nativeQuery = true)
    Optional<AzureUserCredential> findAzureUserCredentialUsingAzureUserId(String azureUserId);

    @Modifying
    @Query("UPDATE AzureUserCredential SET syncStatus = :status WHERE id = :id")
    void updateSyncStatusData(@Param("status") boolean status, @Param("id") Integer id);

    Optional<AzureUserCredential> findByTenantId(String tenantId);
    Optional<AzureUserCredential> findByIdAndWsTenantName(Integer id, String wsTenantName);
}
