package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureApplication;
import com.ws.azureAdIntegration.entity.AzureTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

public interface AzureApplicationRepository extends JpaRepository<AzureApplication, Integer> {

    List<AzureApplication> findAllByAzureTenant(@Param("azureTenant") AzureTenant azureTenant);

//    @Query("SELECT new com.ws.azureAdIntegration.dto.AzureApplicationDto(a.id, a.objectId, a.displayName, a.description, " +
//            "a.homepage, a.publisher, a.disabledByMicrosoftStatus, a.isDeviceOnlyAuthSupported, " +
//            "a.publisherDomain, a.azureCreatedDateTime, a.createdAt, a.wsTenantId, " +
//            "(SELECT new com.ws.azureAdIntegration.dto.AzureAppRolesDto(ar.id, ar.azureId, ar.displayName, ar.description, " +
//            "ar.isEnabled, ar.origin, ar.value, ar.createdAt) " +
//            "FROM AzureAppRoles ar WHERE ar.application = a)) " +
//            "FROM AzureApplication a " +
//            "WHERE a.azureTenant = :azureTenant")
//    List<AzureApplicationDto> findAllByAzureTenant(@Param("azureTenant") AzureTenant azureTenant);
}
