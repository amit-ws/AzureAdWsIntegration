//package com.ws.azureAdIntegration.repository.fromWsBackend;
//
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//
//import java.util.List;
//import java.util.Map;
//
//public interface AzureUserDeviceRelationshipRepository extends JpaRepository<AzureUserDeviceRelationship, Integer> {
//
//    @Query(value = "select " +
//            "u.id as userId, u.azure_id as userAzureId, u.display_name as userName, " +
//            "d.id as deviceId, d.azure_id as deviceAzureid, d.display_name as deviceName, d.operating_system as os, d.operating_system_version as osVersion, d.device_version as deviceVersion " +
//            "from \"user\" u inner join user_device_relationship udr on u.id = udr.user_id inner join device d on d.id = udr.device_id"
//            , nativeQuery = true)
//    List<Map<String, String>> fetchAzureUserDeviceMappings();
//}
