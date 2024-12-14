package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class IdentityDTO {
    String id;
    String principalId;
    String type;
    String name;
    String tenantId;
    String clientId;
}
