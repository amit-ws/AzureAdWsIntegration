package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_namespace_role", schema = "azure_test")
public class K8NamespaceRole extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @OneToMany(mappedBy = "kubernetesNamespaceRole", orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8RolePolicyRule> k8RolePolicyRules = new ArrayList<>();
}
