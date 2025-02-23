package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_role_ref", schema = "azure_test")
public class K8RoleReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String apiGroup;

    @Column(nullable = false)
    String kind;
    @Column(nullable = false)
    String name;  /* stores role_name  */
    @Column(nullable = false)
    String roleUID; /* ROLE_UID of referenced role */

    @Column(nullable = false)
    String clusterId;

    @Column(nullable = false)
    String cloudResourceAccountId;

    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;

    @Column(nullable = false)
    String wsTenantName;
}
