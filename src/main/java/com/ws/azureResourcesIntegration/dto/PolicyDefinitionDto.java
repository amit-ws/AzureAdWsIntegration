package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class PolicyDefinitionDto {
    String id;
    String azureId;
    String policyType;
    String displayName;
    String description;
    Object policyRule;
    String mode;
}
