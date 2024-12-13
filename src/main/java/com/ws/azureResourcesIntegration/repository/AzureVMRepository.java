package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureVMRepository extends JpaRepository<AzureVM, Integer> {
    void deleteAllByAzureTenant(AzureTenant azureTenant);

}
