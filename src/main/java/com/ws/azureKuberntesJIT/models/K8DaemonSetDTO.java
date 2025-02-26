package com.ws.azureKuberntesJIT.models;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.Date;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class K8DaemonSetDTO extends K8MetadataDTO {
    Long id;


    /* To be consumed on the tenant ADMIN side */
    public K8DaemonSetDTO(Long id, String apiVersion, String selfLink, String kind, String resourceVersion, Long generation,
                          String name, String uid, String generateName, OffsetDateTime creationTimestamp, OffsetDateTime deletionTimestamp,
                          Date syncedAt, Date updatedAt, String clusterId, String namespace, CloudProviderType cloudProviderType,
                          String wsTenantName, String cloudResourceAccountId, boolean flag) {
        super(apiVersion, selfLink, kind, resourceVersion, generation, name, uid, generateName, creationTimestamp, deletionTimestamp,
                syncedAt, updatedAt, clusterId, namespace, cloudProviderType, wsTenantName, cloudResourceAccountId, flag);
        this.id = id;
    }

    /* To be consumed on the tenant USER side */
    public K8DaemonSetDTO(Long id, String uid, String name, String clusterId, String resourceAccountId, String wsTenantName, CloudProviderType cloudProviderType, boolean flag) {
        super(uid, name, clusterId, resourceAccountId, wsTenantName, cloudProviderType, flag);
        this.id = id;
    }

}
