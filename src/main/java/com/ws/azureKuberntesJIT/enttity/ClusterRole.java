package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_cluster_role", schema = "azure_test")
public class ClusterRole extends KubernetesMetadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String clusterId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudType;

    @OneToOne
    @JoinColumn(name = "aggregation_rule_id")
    AggregationRule aggregationRule;

    @OneToMany(mappedBy = "clusterRole", orphanRemoval = true, fetch = FetchType.LAZY)
    List<PolicyRule> policyRules = new ArrayList<>();
}
