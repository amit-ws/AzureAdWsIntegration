package com.ws.azureResourcesIntegration.dto;

import com.ws.azureAdIntegration.constants.PublishResourceType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class PublishResourceRequest {
    @NotNull
    String resourceId;
    @NotNull
    String wsTenantName;
    boolean flag;
    @NotNull
    PublishResourceType type;

//    @NotNull
//    String subscriptionId;
}
