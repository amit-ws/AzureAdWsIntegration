package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureUserCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AzureUserCredentialRepository extends JpaRepository<AzureUserCredential, Integer> {
    void deleteByTenantId(String tenantId);
    Optional<AzureUserCredential> findByWsTenantId(Integer wsTenantId);

}
