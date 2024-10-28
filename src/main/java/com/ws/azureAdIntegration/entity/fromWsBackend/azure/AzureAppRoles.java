//package com.ws.azureAdIntegration.entity.fromWsBackend.azure;
//
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//
//import java.util.UUID;
//
//@Entity
//@Table(name = "azure_app_role", schema = "public")
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//public class AzureAppRoles {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Integer id;
//
//    private UUID azureId;
//    private String displayName;
//    private String description;
//    private Boolean isEnabled;
//    private String origin;
//    private String value;
//
//    @ManyToOne
//    AzureApplication applicationId;
//}
