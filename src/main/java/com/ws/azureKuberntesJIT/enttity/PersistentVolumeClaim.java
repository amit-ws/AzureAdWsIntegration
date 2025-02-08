package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_persistent_volume_claim", schema = "azure_test")
public class PersistentVolumeClaim extends KubernetesMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String apiVersion;
    String provisioner;
    String volumeBindingMode;
    Boolean allowVolumeExpansion;
    String reclaimPolicy;
}
