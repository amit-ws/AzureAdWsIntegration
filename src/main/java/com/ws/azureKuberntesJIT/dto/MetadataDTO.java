package com.ws.azureKuberntesJIT.dto;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MetadataDTO {
    String apiVersion;
    String clusterId;
    String selfLink;
    String kind;
    String phase;
    String resourceVersion;
    Long generation;
    String name;
    String uid;
    String namespace;
    String generateName;
    Map<String, String> annotations;
    OffsetDateTime creationTimestamp;
    OffsetDateTime deletionTimestamp;
    CloudProviderType cloudProviderType;
}