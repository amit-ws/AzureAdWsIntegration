//package com.ws.azureKuberntesJIT.models;
//
//import com.ws.azureAdIntegration.constants.CloudProviderType;
//import lombok.*;
//import lombok.experimental.SuperBuilder;
//
//import java.time.OffsetDateTime;
//import java.util.Date;
//
//@EqualsAndHashCode(callSuper = true)
//@Data
//@SuperBuilder
//@AllArgsConstructor
//@NoArgsConstructor
//public class K8NodeDTO extends K8MetadataDTO {
//    Long id;
//    String phase;
//    String externalID;
//    String podCIDR;
//    Boolean unschedulable;
//    String providerID;
//    boolean flag;
//
//    public K8NodeDTO(Long id, String apiVersion, String selfLink, String kind, String resourceVersion, Long generation,
//                     String name, String uid, String generateName, OffsetDateTime creationTimestamp, OffsetDateTime deletionTimestamp,
//                     Date syncedAt, Date updatedAt, String clusterId, String namespace, CloudProviderType cloudProviderType,
//                     String wsTenantName, String cloudResourceAccountId, String phase, String externalID, String podCIDR,
//                     Boolean unschedulable, String providerID, boolean flag) {
//        super(apiVersion, selfLink, kind, resourceVersion, generation, name, uid, generateName, creationTimestamp, deletionTimestamp,
//                syncedAt, updatedAt, clusterId, namespace, cloudProviderType, wsTenantName, cloudResourceAccountId);
//        this.id = id;
//        this.phase = phase;
//        this.externalID = externalID;
//        this.podCIDR = podCIDR;
//        this.unschedulable = unschedulable;
//        this.providerID = providerID;
//        this.flag = flag;
//    }
//
//    /* To be consumed on the tenant USER side */
//    public K8NodeDTO(Long id, String uid, String name, String clusterId, String resourceAccountId, String wsTenantName, CloudProviderType cloudProviderType) {
//        super(uid, name, clusterId, resourceAccountId, wsTenantName, cloudProviderType);
//        this.id = id;
//    }
//
//}
