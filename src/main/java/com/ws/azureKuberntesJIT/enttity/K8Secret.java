package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_secret", schema = "azure_test")
public class K8Secret extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String type;
    Boolean immutable;

    @ElementCollection
    @CollectionTable(name = "kubernetes_secret_string_data", joinColumns = @JoinColumn(name = "secret_id"))
    @MapKeyColumn(name = "data_key")
    @Column(name = "data_value")
    Map<String, String> stringData;
}
