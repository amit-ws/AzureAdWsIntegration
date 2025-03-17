package com.ws.azureResourcesIntegration.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;

@Entity
@Data
@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "azure_user_configure", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserConfigure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    @Column(nullable = false, unique = true)
    String azureId; /* Azure id of the User */
    String azureUserUpn;
    String displayName;
    @Column(nullable = false)
    String wsTenantName;
    @Column(nullable = false, unique = true)
    String email; /* generic email ID (eg: someone@gmail.com) of the User */
    Date createdOn;
}
