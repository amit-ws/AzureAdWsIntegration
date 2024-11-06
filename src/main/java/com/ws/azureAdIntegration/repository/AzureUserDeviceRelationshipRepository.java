package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureDevice;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserDeviceRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AzureUserDeviceRelationshipRepository extends JpaRepository<AzureUserDeviceRelationship, Integer> {

    @Query(value = "select ad from AzureDevice ad LEFT JOIN AzureUserDeviceRelationship audr on ad = audr.azureDevice WHERE audr.azureUser = :azureUser")
    List<AzureDevice> fetchDevicesForUser(AzureUser azureUser);

    void deleteByAzureUser(AzureUser azureUser);

}
