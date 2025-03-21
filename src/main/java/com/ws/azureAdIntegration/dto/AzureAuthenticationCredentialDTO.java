package com.ws.azureAdIntegration.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureAuthenticationCredentialDTO {
    String tenantId;
    String clientId;
    String clientSecret;
}
