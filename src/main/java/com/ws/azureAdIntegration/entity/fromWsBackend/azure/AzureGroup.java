//package com.ws.azureAdIntegration.entity.fromWsBackend.azure;
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
//@Table(name = "azure_group", schema = "public")
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//public class AzureGroup {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Integer id;
//    private String azureId;
//    private String displayName;
//    private String description;
//    private String mail;
//    private String mailNickname;
//    private boolean mailEnabled;
//    private boolean securityEnabled;
//    private String visibility;
//    private OffsetDateTime createdDateTime;
//    private String securityIdentifier;
//}
