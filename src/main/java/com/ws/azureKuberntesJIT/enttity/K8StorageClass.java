package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_storage_class", schema = "azure_test")
public class K8StorageClass extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String apiVersion;
    String provisioner;
    String volumeBindingMode;
    Boolean allowVolumeExpansion;
    String reclaimPolicy;
}



