package com.ws.azureAdIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class AzureGroupResponse {
    String azureId;
    String displayName;
}
