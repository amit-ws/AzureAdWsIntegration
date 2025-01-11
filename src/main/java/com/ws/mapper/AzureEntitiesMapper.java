package com.ws.mapper;

import com.azure.identity.AzureCliCredential;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureUserCredential;
import com.ws.azureResourcesIntegration.entities.AzureRoleAssignment;
import com.ws.azureResourcesIntegration.entities.CustomRoleAssignment;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface AzureEntitiesMapper {
    AzureEntitiesMapper INSTANCE = Mappers.getMapper(AzureEntitiesMapper.class);

    List<CustomRoleAssignment> fromAzureRoleAssignments(List<AzureRoleAssignment> assignments);

    AzureUserCredentialDTO fromAzureUserCredentialDTO(AzureUserCredential azureUserCredential);
}
