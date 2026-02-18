package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_label_selector_requirement", schema = "azure_test")
public class K8LabelSelectorRequirement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String key;
    String operator;

    @ElementCollection
    @CollectionTable(name = "kubernetes_label_selector_requirement_values", joinColumns = @JoinColumn(name = "label_selector_requirement_id"))
    @Column(name = "value")
    List<String> values;

    @Column(nullable = false)
    String clusterRoleUID;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kubernetes_label_selector_id", referencedColumnName = "id")
    K8LabelSelector k8LabelSelector;
}
