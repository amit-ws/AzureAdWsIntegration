package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.*;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_policy_rule", schema = "azure_test")
public class K8RolePolicyRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ElementCollection
    @CollectionTable(name = "kubernetes_policy_rule_verbs", joinColumns = @JoinColumn(name = "policy_rule_id"))
    @Column(name = "verb")
    List<String> verbs;

    @ElementCollection
    @CollectionTable(name = "kubernetes_policy_rule_api_groups", joinColumns = @JoinColumn(name = "policy_rule_id"))
    @Column(name = "api_group")
    List<String> apiGroups;

    @ElementCollection
    @CollectionTable(name = "kubernetes_policy_rule_resources", joinColumns = @JoinColumn(name = "policy_rule_id"))
    @Column(name = "resource")
    List<String> resources;

    @ElementCollection
    @CollectionTable(name = "kubernetes_policy_rule_non_resource_urls", joinColumns = @JoinColumn(name = "policy_rule_id"))
    @Column(name = "non_resource_url")
    List<String> nonResourceURLs;

    @ElementCollection
    @CollectionTable(name = "kubernetes_policy_rule_resource_names", joinColumns = @JoinColumn(name = "policy_rule_id"))
    @Column(name = "resource_name")
    List<String> resourceNames;

    @Column(nullable = false)
    String clusterRoleUID;

    String namespace;

    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;

    @ManyToOne(fetch = FetchType.LAZY)
    K8ClusterRole k8ClusterRole;

    @ManyToOne(fetch = FetchType.LAZY)
    K8NamespaceRole kubernetesNamespaceRole;

}
