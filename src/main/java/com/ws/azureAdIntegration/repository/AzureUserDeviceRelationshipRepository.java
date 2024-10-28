package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureUserDeviceRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;

public interface AzureUserDeviceRelationshipRepository extends JpaRepository<AzureUserDeviceRelationship, Integer> {

    @Query(value = "select ad.* from azure_device ad left join azure_user_device_relationship audr on ad.id = audr.device_id where audr.user_id = :userId",
    nativeQuery = true)
    List<Map<String, Object>> fetchDevicesForUser(Integer userId);


}
