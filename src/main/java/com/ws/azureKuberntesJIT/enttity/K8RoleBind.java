package com.ws.azureKuberntesJIT.enttity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;


@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_role_bind", schema = "azure_test")
public class K8RoleBind extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    K8RoleReference roleRef;

    @JsonIgnore
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "kubernetesRoleBind", orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8RbacSubject> rbacSubjects;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    K8ResourceLevel level;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    K8RoleBindingType bindingType;
}