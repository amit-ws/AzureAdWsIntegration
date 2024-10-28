//package com.ws.azureAdIntegration.entity.fromWsBackend.azure;
//
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//import java.time.OffsetDateTime;
//import java.util.List;
//
//@Entity
//@Table(name = "azure_application", schema = "public")
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//public class AzureApplication {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Integer id;
//
//    private String objectId;
//    private String clientId;
//    private String displayName;
//    private String description;
//    private String homepage;
//    private String publisher;
//    private String disabledByMicrosoftStatus;
//    private Boolean isDeviceOnlyAuthSupported;
//    public String publisherDomain;
//    public OffsetDateTime createdDateTime;
//
//    @ElementCollection
//    public List<String> tags;
//
//}
