package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.*;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_aggregation_rule", schema = "azure_test")
public class K8AggregationRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String clusterRoleUID;  /* CLUSTER typed Kubernetes Role */

    @JsonIgnore
    @OneToMany(mappedBy = "k8AggregationRule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8LabelSelector> k8LabelSelectors;

    @JsonIgnore
    @OneToOne(fetch = FetchType.LAZY)
    K8Role kubernetesRole;
}
