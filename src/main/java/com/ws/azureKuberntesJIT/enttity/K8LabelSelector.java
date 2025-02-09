package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.*;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_label_selector", schema = "azure_test")
public class K8LabelSelector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ElementCollection
    @CollectionTable(name = "kubernetes_label_selector_match_labels", joinColumns = @JoinColumn(name = "label_selector_id"))
    @MapKeyColumn(name = "label_key")
    @Column(name = "label_value")
    Map<String, String> matchLabels;

    @ManyToOne(fetch = FetchType.LAZY)
    K8AggregationRule k8AggregationRule;

    @OneToMany(mappedBy = "k8LabelSelector")
    List<K8LabelSelectorRequirement> matchExpressions;

    @Column(nullable = false)
    String clusterRoleUID;

    String namespace;
}
