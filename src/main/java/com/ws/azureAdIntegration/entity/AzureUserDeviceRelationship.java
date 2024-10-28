package com.ws.azureAdIntegration.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "azure_user_device_relationship", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserDeviceRelationship {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    AzureUser azureUser;
    String azureUserId;

    @OneToOne
    @JoinColumn(name = "device_id", referencedColumnName = "id")
    AzureDevice azureDevice;
    String azureDeviceId;
}
