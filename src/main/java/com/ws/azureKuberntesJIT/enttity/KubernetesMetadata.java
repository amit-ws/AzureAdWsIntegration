package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@MappedSuperclass
public class KubernetesMetadata {
    String apiVersion;
    String selfLink;
    String kind;
    String phase;
    String resourceVersion;
    Long generation;
    String name;
    String uid;
    String generateName;
    OffsetDateTime creationTimestamp;
    OffsetDateTime deletionTimestamp;

    @ElementCollection
    @CollectionTable(name = "kubernetes_metadata_annotations", joinColumns = @JoinColumn(name = "metadata_id"))
    @MapKeyColumn(name = "annotation_key")
    @Column(name = "annotation_value")
    Map<String, String> annotations;

    String clusterId;
    String namespace;
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;
}
