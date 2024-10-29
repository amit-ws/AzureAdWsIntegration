package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureDevice;
import com.ws.azureAdIntegration.entity.AzureTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AzureDeviceRepository extends JpaRepository<AzureDevice, Integer> {

    List<AzureDevice> findAllByAzureTenant(AzureTenant azureTenant);
    void deleteAllByAzureTenant(AzureTenant azureTenant);

}
