//package com.ws.azureAdIntegration.controller.fromWsBackend.azure;
//
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.Map;
//
//@RestController
//@RequestMapping("/api/azure/auth")
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class AuthController {
//    final AzureUserCredentialRepository userCredentialRepository;
//
//    @Autowired
//    public AuthController(AzureUserCredentialRepository userCredentialRepository) {
//        this.userCredentialRepository = userCredentialRepository;
//    }
//
//    @PostMapping("/configure")
//    public ResponseEntity creatAzureConfiguration(@RequestBody Map<String, String> payload) {
//        String clientId = payload.get("client_id");
//        String tenant = payload.get("tenant_id");
//        String clientSecret = payload.get("client_secret");
//        String objectId = payload.get("object_id");
//
//        AzureUserCredential userCredential = AzureUserCredential.builder()
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .tenantId(tenant)
//                .objectId(objectId)
//                .userId(tenant + clientId + clientSecret)
//                .build();
//        userCredentialRepository.save(userCredential);
//        return ResponseEntity.ok("User configure successfully!");
//    }
//
//}
//
//
