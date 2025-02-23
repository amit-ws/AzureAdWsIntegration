package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.Date;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@MappedSuperclass
public class K8Metadata {
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

//    @ElementCollection
//    @CollectionTable(name = "kubernetes_metadata_annotations", joinColumns = @JoinColumn(name = "metadata_id"))
//    @MapKeyColumn(name = "annotation_key")
//    @Column(name = "annotation_value")
//    Map<String, String> annotations = new HashMap<>();

    Date syncedAt;
    Date updatedAt;

    @Column(nullable = false)
    String clusterId;
    String namespace;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;

    @Column(nullable = false)
    String wsTenantName;
    @Column(nullable = false)
    String cloudResourceAccountId; /* for Azure = subscription_id.  For GCP = Project_id*/
}
