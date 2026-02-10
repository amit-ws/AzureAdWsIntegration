package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureResourcesIntegration.constant.RequestStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Entity
@Table(name = "Kubernetes_custom_resource_request", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8CustomResourceRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    UUID id;

    // Role related fields
    @Embedded
    K8RoleRequest roleRequest;

    // Role Binding related fields
    @Embedded
    K8RoleBindRequest roleBindRequest;

    // Request related fields
    @Enumerated(EnumType.STRING)
    RequestStatus status;
    @Builder.Default
    Date requestedAt = new Date();
    Date updatedAt;
    Long expiryTimeAmount;
    LocalDateTime validFrom;
    LocalDateTime validTo;
    @Column(nullable = false)
    String wsUserEmail; /* Ws Tenant user email */

    // Generic fields
    String message;
    @Column(nullable = false)
    String clusterId;
    @Column(nullable = false)
    String cloudResourceAccountId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    CloudProviderType cloudType;
    @Column(nullable = false)
    String wsTenantName;

    String clusterName;

    @Column(name = "cert_csr_name", columnDefinition = "TEXT")
    String certCsrName;
    @Column(name = "csr_private_key_pem", columnDefinition = "TEXT")
    private String privateKeyPem;
    @Column(name = "csr_signed_cert", columnDefinition = "TEXT")
    String certificatePem;
}
