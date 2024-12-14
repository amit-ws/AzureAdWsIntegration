package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class ServicePrincipleDTO {
    String id;
    String applicationId;
    List<String> servicePrincipalNames;
}
