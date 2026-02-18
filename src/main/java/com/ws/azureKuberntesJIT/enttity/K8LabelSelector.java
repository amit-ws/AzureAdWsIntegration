package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.*;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @JsonIgnore
    @OneToMany(mappedBy = "k8LabelSelector", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8LabelSelectorRequirement> matchExpressions;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kubernetes_aggregation_rule_id", referencedColumnName = "id")
    K8AggregationRule k8AggregationRule;

    @Column(nullable = false)
    String clusterRoleUID;
}
