package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_role_ref", schema = "azure_test")
public class K8RoleReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String kind;
    String name;
    String apiGroup;

    String clusterId;
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;
}
