package com.ws.azureAdIntegration.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CreateAzureConfiguration {
    @NotNull(message = "client_id is required")
    @JsonProperty("client_id")
    String clientId;

    @NotNull(message = "azure tenant_id is required")
    @JsonProperty("az_tenant_id")
    String tenantId;

    @NotNull(message = "client_secret is required")
    @JsonProperty("client_secret")
    String clientSecret;

    @NotNull(message = "azure subscriptionId is required")
    @JsonProperty("subscription_id")
    String subscriptionId;

//    @NotNull(message = "azure application object_id is required")
//    @JsonProperty("object_id")
//    String objectId;

//    @NotNull(message = "Please provide WhiteSwan tenant email")
//    @JsonProperty("ws_tenant_email")
//    String wsTenantEmail;
}
