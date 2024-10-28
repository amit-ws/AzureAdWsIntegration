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
//@Table(name = "azure_tenant", schema = "public")
//@Data
//@AllArgsConstructor
//@NoArgsConstructor
//@Builder
//public class AzureTenant {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    private Integer id;
//    private String azureId;
//    private String displayName;
//    private String countryLetterCode;
//    private String verifiedDomains;
//    public String postalCode;
//    public String preferredLanguage;
//    public String state;
//    public String street;
//    public String tenantType;
//    public OffsetDateTime createdDateTime;
//}
