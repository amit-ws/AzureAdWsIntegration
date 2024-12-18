package com.ws.azureResourcesIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureTenant;
import com.ws.azureResourcesIntegration.entities.AzureVM;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AzureVMRepository extends JpaRepository<AzureVM, Integer> {
    List<AzureVM> findAllByAzureTenant(AzureTenant azureTenant);

}
