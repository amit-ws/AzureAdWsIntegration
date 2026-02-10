//package com.ws.azureKuberntesJIT.service;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.ws.azureKuberntesJIT.dto.CertificateSigningRequestData;
//import com.ws.certificateJIT.k8.CertificateConfig;
//import com.ws.certificateJIT.k8.CertificateUtil;
//import com.ws.certificateJIT.k8.EncodingUtil;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.apis.CertificatesV1Api;
//import io.kubernetes.client.openapi.models.V1CertificateSigningRequest;
//import io.kubernetes.client.openapi.models.V1CertificateSigningRequestCondition;
//import io.kubernetes.client.openapi.models.V1CertificateSigningRequestSpec;
//import io.kubernetes.client.openapi.models.V1ObjectMeta;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.security.KeyPair;
//import java.util.*;
//
//@Slf4j
//@Service
//public class K8CertificateSigningService {
//
//    private final ObjectMapper objectMapper = new ObjectMapper();
//    private final CertificateConfig certificateConfig;
//
//    @Autowired
//    public K8CertificateSigningService(
//            CertificateConfig certificateConfig) {
//        this.certificateConfig = certificateConfig;
//    }
//
//
//    public CertificateSigningRequestData createCertificateSigningRequest(
//            String userId,
//            String userName,
//            List<String> groups,
//            Integer expirationSeconds,
//            ApiClient apiClient) throws Exception {
//
//        log.info("Creating CSR for user: {}", userName);
//
//        if (expirationSeconds == null) {
//            expirationSeconds = certificateConfig.getDefaultExpirationSeconds();
//        }
//        if (expirationSeconds > certificateConfig.getMaxExpirationSeconds()) {
//            expirationSeconds = certificateConfig.getMaxExpirationSeconds();
//            log.info("User requested for CERT based JIT time exceeded the max threshold: {}", expirationSeconds);
//        }
//
//        KeyPair keyPair = CertificateUtil.generateKeyPair();
//        String csrPem = CertificateUtil.generateCSR(keyPair, userName, groups);
//        String csrBase64 = EncodingUtil.compactBase64(
//                EncodingUtil.encodeBase64(csrPem)
//        );
//
//        String csrName = "jit-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
//        V1CertificateSigningRequest csrObject = buildCSRObject(
//                csrName,
//                csrBase64,
//                expirationSeconds,
//                groups
//        );
//
//        CertificatesV1Api api = new CertificatesV1Api(apiClient);
//        api.createCertificateSigningRequest(csrObject).execute();
//
//        log.info("CSR created: {}", csrName);
//
//        String privateKeyPem = CertificateUtil.encodePrivateKeyPEM(keyPair);
//        String privateKeyBase64 = EncodingUtil.encodeBase64(privateKeyPem);
//
//        return CertificateSigningRequestData.builder()
//                .csrName(csrName)
//                .csrPem(csrPem)
//                .csrBase64(csrBase64)
//                .privateKeyPem(privateKeyPem)
//                .privateKeyBase64(privateKeyBase64)
//                .userName(userName)
//                .groups(groups)
//                .expirationSeconds(expirationSeconds)
//                .createdTimestamp(System.currentTimeMillis())
//                .expirationTimestamp(System.currentTimeMillis() + (expirationSeconds * 1000L))
//                .build();
//    }
//
//
//    private V1CertificateSigningRequest buildCSRObject(
//            String csrName,
//            String csrBase64,
//            Integer expirationSeconds,
//            List<String> groups) {
//
//        V1CertificateSigningRequest csr = new V1CertificateSigningRequest();
//        csr.setApiVersion("certificates.k8s.io/v1");
//        csr.setKind("CertificateSigningRequest");
//
//        V1ObjectMeta metadata = new V1ObjectMeta();
//        metadata.setName(csrName);
//        csr.setMetadata(metadata);
//
//
//        byte[] csrBytes = Base64.getDecoder().decode(csrBase64);
//
//
//        V1CertificateSigningRequestSpec spec = new V1CertificateSigningRequestSpec();
//        spec.setRequest(csrBytes);
//        spec.setSignerName(certificateConfig.getSignerName());
//        spec.setExpirationSeconds(expirationSeconds);
//        spec.setUsages(Arrays.asList("digital signature", "key encipherment", "client auth"));
//
//        if (groups != null && !groups.isEmpty()) {
//            spec.setGroups(groups);
//        }
//
//        csr.setSpec(spec);
//        return csr;
//    }
//
//
//    public void approveCertificateSigningRequest(String csrName, String token, ApiClient apiClient) {
//        try {
//            log.info("Approving CSR: {}", csrName);
//            V1CertificateSigningRequestCondition approvalCondition =
//                    new V1CertificateSigningRequestCondition()
//                            .type("Approved")
//                            .status("True")
//                            .reason("JITAccessApproved")
//                            .message("Approved by JIT Access Service");
//
//            Map<String, Object> statusPatch = new HashMap<>();
//            Map<String, Object> status = new HashMap<>();
//            status.put("conditions", Arrays.asList(approvalCondition));
//            statusPatch.put("status", status);
//
//            String patchJson = objectMapper.writeValueAsString(statusPatch);
//            log.info("Patch JSON: {}", patchJson);
//
//            String basePath = apiClient.getBasePath();
//            String url = basePath + "/apis/certificates.k8s.io/v1/certificatesigningrequests/" + csrName + "/approval";
//
//            log.info("Approval URL: {}", url);
//
//            okhttp3.MediaType mediaType = okhttp3.MediaType.parse("application/strategic-merge-patch+json");
//            okhttp3.RequestBody body = okhttp3.RequestBody.create(patchJson, mediaType);
//
//            okhttp3.Request request = new okhttp3.Request.Builder()
//                    .url(url)
//                    .patch(body)
//                    .addHeader("Content-Type", "application/strategic-merge-patch+json")
//                    .addHeader("Accept", "application/json")
//                    .addHeader("Authorization", "Bearer " + token)
//                    .build();
//
//            okhttp3.OkHttpClient client = apiClient.getHttpClient();
//            okhttp3.Response response = client.newCall(request).execute();
//
//            if (!response.isSuccessful()) {
//                String responseBody = response.body() != null ? response.body().string() : "No response body";
//                log.error("Failed to approve CSR. Status: {}, Body: {}", response.code(), responseBody);
//                log.error("Response headers: {}", response.headers());
//                throw new Exception("Failed to approve CSR. Status: " + response.code() + ", Body: " + responseBody);
//            }
//
//            log.info("CSR approved successfully: {}", csrName);
//        } catch (Exception ex) {
//            log.error("Error while approving CSR: {}", csrName);
//            log.error("error: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//    }
//
//
//    public String getSignedCertificate(String csrName, int maxRetries, ApiClient apiClient) throws Exception {
//        log.info("Retrieving signed certificate for CSR: {}", csrName);
//
//        CertificatesV1Api api = new CertificatesV1Api(apiClient);
//
//        for (int i = 0; i < maxRetries; i++) {
//            V1CertificateSigningRequest csr = api.readCertificateSigningRequest(csrName).execute();
//
//            if (csr.getStatus() != null && csr.getStatus().getCertificate() != null) {
//                log.info("Certificate retrieved: {}", csrName);
//
//                byte[] certBytes = csr.getStatus().getCertificate();
//                String certBase64 = Base64.getEncoder().encodeToString(certBytes);
//
//                log.info("Certificate (base64): {}", certBase64);
//                return certBase64;
//            }
//
//            log.debug("Certificate not yet signed, retrying... ({}/{})", i + 1, maxRetries);
//            Thread.sleep(1000);
//        }
//
//        throw new Exception("Certificate signing timeout for CSR: " + csrName);
//    }
//
//
//    public void deleteCertificateSigningRequest(String csrName, ApiClient apiClient) {
//        try {
//            log.info("Deleting CSR: {}", csrName);
//            CertificatesV1Api api = new CertificatesV1Api(apiClient);
//            api.deleteCertificateSigningRequest(csrName).execute();
//            log.info("CSR deleted: {}", csrName);
//        } catch (Exception ex) {
//            log.error("Error while deleting CSR: {}", csrName);
//            log.error("error: {}", ex.getMessage());
//            throw new RuntimeException(ex.getMessage());
//        }
//    }
//
//
//}
