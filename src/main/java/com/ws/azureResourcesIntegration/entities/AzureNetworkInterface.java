package com.ws.azureResourcesIntegration.entities;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.util.Date;


@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Data
@Entity
@FieldDefaults(level = AccessLevel.PRIVATE)
@Table(name = "azure_network_interface", schema = "azure_test")
public class AzureNetworkInterface extends BaseAzureResource{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false)
    String azureId;
    @Column(nullable = false)
    String name;
    @Column(nullable = false)
    String virtualMachineId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_vm_id", referencedColumnName = "id")
    AzureVM azureVM;
}
