package com.ws.azureAdIntegration.entity;


import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;

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
    String clientId;
    String tenantId;
    String clientSecret;
    String subscriptionId;

    Date createdAt;
    String wsTenantName; // Whiteswan account organization name
}
