package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Entity
@Table(name = "kubernetes_namespace", schema = "azure_test")
public class K8Namespace extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String clusterId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudType;

    String generateName;
}


