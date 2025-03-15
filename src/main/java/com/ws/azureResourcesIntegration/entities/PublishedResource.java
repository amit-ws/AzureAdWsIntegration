package com.ws.azureResourcesIntegration.entities;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureAdIntegration.constants.PublishResourceType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;

@Entity
@Table(name = "published_resource",
        schema = "azure_test",
        uniqueConstraints = {@UniqueConstraint(name = "uk_published_resource_id_ws_tenant_name",
                columnNames = {"resourceId", "resourceType", "resourceAccountId", "wsTenantName"})})
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PublishedResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String resourceId; /* For Azure: azure_id, For K8: UID*/
    @Column(nullable = false)
    String wsTenantName;
    @Column(nullable = false)
    String resourceAccountId; /* subscriptionId for Azure, accountId for AWS, projectId for GCP */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    PublishResourceType resourceType; /* eg: VIRTUAL_MACHINE */
    @Builder.Default
    Date createdAt = new Date();
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;
    String clusterId; /* [NOT NULL] for K* resources type */
}
