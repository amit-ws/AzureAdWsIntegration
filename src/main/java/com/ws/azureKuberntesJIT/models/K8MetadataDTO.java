package com.ws.azureKuberntesJIT.models;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.Date;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class K8MetadataDTO {
    String apiVersion;
    String selfLink;
    String kind;
    String resourceVersion;
    Long generation;
    String name;
    String uid;
    String generateName;
    OffsetDateTime creationTimestamp;
    OffsetDateTime deletionTimestamp;
    Date syncedAt;
    Date updatedAt;
    String clusterId;
    String namespace;
    CloudProviderType cloudProviderType;
    String wsTenantName;
    String cloudResourceAccountId;
    boolean isPublished;


    public K8MetadataDTO(String uid, String name, String clusterId, String resourceAccountId, String wsTenantName, CloudProviderType cloudProviderType, boolean isPublished) {
        this.uid = uid;
        this.name = name;
        this.clusterId = clusterId;
        this.cloudResourceAccountId = resourceAccountId;
        this.wsTenantName = wsTenantName;
        this.cloudProviderType = cloudProviderType;
        this.isPublished = isPublished;
    }
}
