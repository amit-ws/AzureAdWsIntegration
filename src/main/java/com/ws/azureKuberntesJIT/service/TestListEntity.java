package com.ws.azureKuberntesJIT.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "test_list_entity", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TestListEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String name;

    @JsonIgnore
    @ManyToOne
    @JoinColumn(name = "parent_id", referencedColumnName = "id")
    TestEntity testEntity;
}
