package com.ws.azureResourcesIntegration.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class DBServerDTO {
    String serverId;
    String serverName;
    String type;
    String serverVersion;
    String region;
    String resourceGroup;
    LocalDateTime createdDate;
    String status;
    List<DatabaseDTO> databases = new ArrayList<>();

    String kind;
    String state;
    Boolean managedServiceIdentityEnabled;
    String managedServiceIdentityType;
    String publicNetworkAccess;
    String resourceGroupName;
    String version;
    String innerModelState;
    String administratorId;
    String administratorType;
    String administratorSignInName;
    List<String> privateEndpointConnectionIds;
    List<String> privateEndpointIds;

    String location;
    String administratorLogin;
}
