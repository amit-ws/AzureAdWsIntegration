package com.ws.azureKuberntesJIT.service;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.Set;

@Entity
@Table(name = "test_entity", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String name;

    @JsonIgnore
    @OneToMany(mappedBy = "testEntity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    List<TestListEntity> entities;
}
