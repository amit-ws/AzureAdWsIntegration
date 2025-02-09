package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.*;
import lombok.Data;

import java.util.List;

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
    String clusterRoleUID;

    String namespace;

    @OneToMany(mappedBy = "k8AggregationRule")
    List<K8LabelSelector> k8LabelSelectors;
}
