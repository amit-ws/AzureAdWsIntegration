package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8RoleKind;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Set;

@EqualsAndHashCode(callSuper = true, exclude = {"k8RolePolicyRules"})
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Data
@Table(name = "kubernetes_role", schema = "azure_test")
public class K8Role extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Enumerated(EnumType.STRING)
    K8ResourceLevel roleLevel;
    @Enumerated(EnumType.STRING)
    K8RoleKind roleKind;

    @JsonIgnore
    @OneToMany(mappedBy = "kubernetesRole", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    Set<K8RolePolicyRule> k8RolePolicyRules;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, mappedBy = "kubernetesRole")
    K8AggregationRule kubernetesAggregationRule; /* null for Namespaced roles */
}
