package com.ws.azureAdIntegration.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CreateAzureConfiguration {
    @NotNull(message = "client_id is required")
//    @JsonProperty("client_id")
    String clientId;

    @NotNull(message = "azure tenant_id is required")
//    @JsonProperty("az_tenant_id")
    String tenantId;

    @NotNull(message = "client_secret is required")
//    @JsonProperty("client_secret")
    String clientSecret;

//    @JsonProperty("subscription_id")
    String subscriptionId;

//    @JsonProperty("subscription_ids")
    Set<String> subscriptionIds;

    @NotNull(message = "Please provide WhiteSwan tenant name")
//    @JsonProperty("ws_tenant_name")
    String wsTenantName;
}
