package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AzureTenantRepository extends JpaRepository<AzureTenant, Integer> {
    Optional<AzureTenant> findByAzureId(String azureTenantId);

    Optional<AzureTenant> findByAzureIdAndWsTenantName(String azureTenantId, String wsTenantName);

    Optional<AzureTenant> findByWsTenantName(String wsTenantName);

    void deleteByAzureId(String azureTenantId);

    void deleteById(Integer id);

//    @Modifying
//    @Query(value = "DELETE FROM azure_tenant WHERE azure_id = :tenantId and ws_tenant_name = :wsTenantName CASCADE", nativeQuery = true)
//    void deleteAzureTenantCascade(@Param("azure_id") String tenantId, @Param("ws_tenant_name") String wsTenantName);

//    @Modifying
//    @Query(value = "DELETE FROM azure_tenant WHERE azure_id = :tenantId AND ws_tenant_name = :wsTenantName", nativeQuery = true)
//    void deleteAzureTenantCascade(@Param("tenantId") String tenantId, @Param("wsTenantName") String wsTenantName);


    void deleteByAzureIdAndWsTenantName(String azureId, String wsTenantName);

}
