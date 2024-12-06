package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class VmDTO {
    String vmId;
    String instanceId;
    String name;
    String computerName;
    String powerState;
    String size;
    String osType;
    String publicIPInstanceId;
    String resourceGroupName;
    Integer osDiskSize;
    String region;
    String securityType;
    String type;
    List<String> zones;
    String resourceIdentityType;
    String ipAddress;
}

