package com.ws.azureAdIntegration.entity;


import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;
import java.util.List;
import java.util.Set;

@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "azure_user_credential", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    @Column(unique = true)
    String clientId;
    String tenantId;
    String clientSecret;
    @Column(unique = true)
    String subscriptionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "azure_user_credential_subscription_ids", joinColumns = @JoinColumn(name = "azure_user_credential_id"))
    @Column(name = "subscription_id")
    Set<String> subscriptionIds;

    @Column(columnDefinition = "boolean default false")
    boolean syncStatus;

    Date createdAt;
    Date updatedAt;
    String wsTenantName; // WhiteSwan account organization name
}
