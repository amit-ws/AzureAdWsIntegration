package com.ws.azureResourcesIntegration.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserConfigureRequest {
    @NotNull(message = "Azure id is required for the user")
    String azureId;
    String displayName;
    @NotNull(message = "Please provide tenant name")
    String wsTenantName;
    @NotNull(message = "Please provide user email")
    String email;
}
