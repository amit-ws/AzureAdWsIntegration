package com.ws.azureAdIntegration.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AzureUserCredentialDTO {
    Integer id;
    String clientId;
    String tenantId;
    String clientSecret;
    String subscriptionId;
    Date createdAt;
    Date updatedAt;
    boolean syncStatus;
    String wsTenantName;
}
