package com.ws.azureAdIntegration.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.graph.models.Device;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.Date;

@Entity
@Table(name = "azure_device", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureDevice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    String azureId; // Object_id from azure response
    String deviceId; // device_id sent by azure
    String displayName;
    String operatingSystem;
    String operatingSystemVersion;
    boolean accountEnabled;
    int deviceVersion;
    OffsetDateTime azureRegistrationDateTime;

    Date createdAt;
    Integer wsTenantId; // Whiteswan account organization id

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ws_azure_tenant_id", referencedColumnName = "id")
    AzureTenant azureTenant;
//    Integer azureTenantId;

    @JsonIgnore
    @OneToOne(mappedBy = "azureDevice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    AzureUserDeviceRelationship azureUserDeviceRelationship;


    public static AzureDevice createFromGraphDevice(Device graphDevice, AzureDevice azureDevice) {
        azureDevice.setAzureId(graphDevice.id);
        azureDevice.setDeviceId(graphDevice.deviceId);
        azureDevice.setDisplayName(graphDevice.displayName);
        azureDevice.setOperatingSystem(graphDevice.operatingSystem);
        azureDevice.setOperatingSystemVersion(graphDevice.operatingSystemVersion);
        azureDevice.setAccountEnabled(Boolean.TRUE.equals(graphDevice.accountEnabled));
        azureDevice.setDeviceVersion(graphDevice.deviceVersion);
        azureDevice.setAzureRegistrationDateTime(graphDevice.registrationDateTime);
        azureDevice.setCreatedAt(new Date());
        return azureDevice;
    }

}
