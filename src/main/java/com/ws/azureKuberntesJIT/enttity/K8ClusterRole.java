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
public class K8ClusterRole extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @OneToOne
    @JoinColumn(name = "aggregation_rule_id")
    K8AggregationRule k8AggregationRule;

    @OneToMany(mappedBy = "k8ClusterRole", orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8RolePolicyRule> k8RolePolicyRules = new ArrayList<>();
}
