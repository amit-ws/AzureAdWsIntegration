//package com.ws.certificateJIT;
//
//
//import com.ws.certificateJIT.azure.AzureCertificateJITService;
//import com.ws.certificateJIT.k8.AccessRequest;
//import com.ws.certificateJIT.k8.AccessResponse;
//import com.ws.certificateJIT.k8.JITAccessService;
//import com.ws.certificateJIT.k8.K8_SA_CertificateBasedJIT;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//@Slf4j
//@RestController
//@RequestMapping("/api/v1/certificate-jit")
//public class AccessController {
//
//
//    private final AzureCertificateJITService azureCertificateJITService;
//
//    private final JITAccessService jitAccessService;
//
//    private final K8_SA_CertificateBasedJIT k8SaCertificateBasedJIT;
//
//    public AccessController(AzureCertificateJITService azureCertificateJITService, JITAccessService jitAccessService, K8_SA_CertificateBasedJIT k8SaCertificateBasedJIT) {
//        this.azureCertificateJITService = azureCertificateJITService;
//        this.jitAccessService = jitAccessService;
//        this.k8SaCertificateBasedJIT = k8SaCertificateBasedJIT;
//    }
//
//    /**
//     * Grant JIT access
//     * POST /api/v1/access/grant
//     */
//    @PostMapping("/k8")
//    public ResponseEntity<AccessResponse> grantAccess(@RequestBody AccessRequest request) {
//        try {
//            log.info("Received access request for user: {}", request.getUserName());
//
//            if (request.getUserId() == null || request.getUserName() == null) {
//                return ResponseEntity
//                        .badRequest()
//                        .body(AccessResponse.builder()
//                                .status("FAILED")
//                                .message("Missing required fields: userId, userName, namespace")
//                                .build());
//            }
//
//            if (request.getDurationSeconds() == null) {
//                request.setDurationSeconds(3600);
//            }
//
//            AccessResponse response = jitAccessService.grantJITAccess(request);
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            log.error("Error granting access: ", e);
//            return ResponseEntity
//                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(AccessResponse.builder()
//                            .status("FAILED")
//                            .message("Error: " + e.getMessage())
//                            .build());
//        }
//    }
//
//
//    @PostMapping("/azure")
//    public ResponseEntity<String> requestJitAccess(
//            @RequestParam String requestingUserId,
//            @RequestParam String resourceScope,
//            @RequestParam String roleDefinitionId,
//            @RequestParam(defaultValue = "600") long expirationSeconds) {
//
//        String bashScript = azureCertificateJITService.generateAzureJitAccessScript(
//                requestingUserId,
//                resourceScope,
//                roleDefinitionId,
//                expirationSeconds
//        );
//
//        return ResponseEntity.ok()
//                .header("Content-Disposition", "attachment; filename=azure-jit-access.sh")
//                .contentType(MediaType.TEXT_PLAIN)
//                .body(bashScript);
//    }
//
//
//    @GetMapping("/k8-sa")
//    public ResponseEntity<String> createServiceAccountWithJit(
//            @RequestParam String saName,
//            @RequestParam String targetResource,
//            @RequestParam(defaultValue = "default") String namespace,
//            @RequestParam(defaultValue = "3600") long duration) {
//
//        try {
//            String result = k8SaCertificateBasedJIT.createServiceAccountWithJit(
//                    saName,
//                    targetResource,
//                    namespace,
//                    duration
//            );
//
//            return ResponseEntity.ok(result);
//
//        } catch (Exception e) {
//            return ResponseEntity.status(500).body("Error: " + e.getMessage());
//        }
//    }
//}
