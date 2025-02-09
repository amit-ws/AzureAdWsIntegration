package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureKuberntesJIT.constant.RoleBindingType;
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

    @ManyToOne
    @JoinColumn(name = "role_ref_id", referencedColumnName = "id")
    K8RoleReference roleRef;

    @OneToMany(mappedBy = "kubernetesRoleBind", orphanRemoval = true, fetch = FetchType.LAZY)
    List<K8RbacSubject> rbacSubjects;

    @Enumerated(EnumType.STRING)
    RoleBindingType roleBindingType;
}