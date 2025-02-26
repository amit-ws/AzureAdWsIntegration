package com.ws.azureResourcesIntegration.entities;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Date;


@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@Table(name = "azure_network_interface", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureNetworkInterface {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false)
    String azureId;
    @Column(nullable = false)
    String name;
    @Column(nullable = false)
    String virtualMachineId;
    String resourceGroupName;
    String subscriptionId;
    @Column(nullable = false)
    Date syncedAt;
    String wsTenantName;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_vm_id", referencedColumnName = "id")
    AzureVM azureVM;
}
