//package com.ws.azureAdIntegration.entity.fromWsBackend.azure;
//
//
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//import java.time.OffsetDateTime;
//
//@Entity
//@Table(name = "azure_device", schema = "public")
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//public class AzureDevice {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Integer id;
//    private String azureId; // Object id from azure response
//    private String deviceId;
//    private String displayName;
//    private String operatingSystem;
//    private String operatingSystemVersion;
//    private boolean accountEnabled;
//    private int deviceVersion;
//    private OffsetDateTime registrationDateTime;
//}
