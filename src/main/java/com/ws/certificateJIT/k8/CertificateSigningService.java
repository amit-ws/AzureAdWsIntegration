package com.ws.certificateJIT.k8;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CertificatesV1Api;
import io.kubernetes.client.openapi.models.V1CertificateSigningRequest;
import io.kubernetes.client.openapi.models.V1CertificateSigningRequestCondition;
import io.kubernetes.client.openapi.models.V1CertificateSigningRequestSpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.util.*;

@Slf4j
@Service
public class CertificateSigningService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApiClient apiClient;
    private final CertificateConfig certificateConfig;

    @Autowired
    public CertificateSigningService(
            ApiClient apiClient,
            CertificateConfig certificateConfig) {
        this.apiClient = apiClient;
        this.certificateConfig = certificateConfig;
    }

    /**
     * Create and submit a CertificateSigningRequest to Kubernetes
     */
    public CertificateSigningRequestData createCertificateSigningRequest(
            String userId,
            String userName,
            List<String> groups,
            Integer expirationSeconds) throws Exception {

        log.info("Creating CSR for user: {}", userName);

        if (expirationSeconds == null) {
            expirationSeconds = certificateConfig.getDefaultExpirationSeconds();
        }
        if (expirationSeconds > certificateConfig.getMaxExpirationSeconds()) {
            expirationSeconds = certificateConfig.getMaxExpirationSeconds();
        }

        KeyPair keyPair = CertificateUtil.generateKeyPair();
        String csrPem = CertificateUtil.generateCSR(keyPair, userName, groups);
        String csrBase64 = EncodingUtil.compactBase64(
                EncodingUtil.encodeBase64(csrPem)
        );

        String csrName = "jit-" + userId + "-" + UUID.randomUUID().toString().substring(0, 8);
        V1CertificateSigningRequest csrObject = buildCSRObject(
                csrName,
                csrBase64,
                expirationSeconds,
                groups
        );

        CertificatesV1Api api = new CertificatesV1Api(apiClient);
        api.createCertificateSigningRequest(csrObject).execute();

        log.info("CSR created: {}", csrName);

        String privateKeyPem = CertificateUtil.encodePrivateKeyPEM(keyPair);
        String privateKeyBase64 = EncodingUtil.encodeBase64(privateKeyPem);

        return CertificateSigningRequestData.builder()
                .csrName(csrName)
                .csrPem(csrPem)
                .csrBase64(csrBase64)
                .privateKeyPem(privateKeyPem)
                .privateKeyBase64(privateKeyBase64)
                .userName(userName)
                .groups(groups)
                .expirationSeconds(expirationSeconds)
                .createdTimestamp(System.currentTimeMillis())
                .expirationTimestamp(System.currentTimeMillis() + (expirationSeconds * 1000L))
                .build();
    }



    public void approveCertificateSigningRequest(String csrName) throws Exception {
        log.info("Approving CSR: {}", csrName);

        V1CertificateSigningRequestCondition approvalCondition =
                new V1CertificateSigningRequestCondition()
                        .type("Approved")
                        .status("True")
                        .reason("JITAccessApproved")
                        .message("Approved by JIT Access Service");

        Map<String, Object> statusPatch = new HashMap<>();
        Map<String, Object> status = new HashMap<>();
        status.put("conditions", Arrays.asList(approvalCondition));
        statusPatch.put("status", status);

        String patchJson = objectMapper.writeValueAsString(statusPatch);
        log.info("Patch JSON: {}", patchJson);

        String basePath = apiClient.getBasePath();
        String url = basePath + "/apis/certificates.k8s.io/v1/certificatesigningrequests/" + csrName + "/approval";

        log.info("Approval URL: {}", url);

        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("application/strategic-merge-patch+json");
        okhttp3.RequestBody body = okhttp3.RequestBody.create(patchJson, mediaType);

        // ✅ Get auth from apiClient and add it manually

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("Content-Type", "application/strategic-merge-patch+json")
                .addHeader("Accept", "application/json")
                .addHeader("Authorization", "Bearer " + "2m1cdratgzh42n8k2n7rdd5ovazvn4ymgvdeb5bk2vjp94o5wqr8vwgfr0m863f8c0lwzr7rtmb5dy8dzwafp09kj4jrcqt5n49v4qsc9sls018c2nt20u9pyoqahqhi")  // ✅ Add auth header
                .build();

        okhttp3.OkHttpClient client = apiClient.getHttpClient();
        okhttp3.Response response = client.newCall(request).execute();

        if (!response.isSuccessful()) {
            String responseBody = response.body() != null ? response.body().string() : "No response body";
            log.error("Failed to approve CSR. Status: {}, Body: {}", response.code(), responseBody);
            log.error("Response headers: {}", response.headers());
            throw new Exception("Failed to approve CSR. Status: " + response.code() + ", Body: " + responseBody);
        }

        log.info("CSR approved successfully: {}", csrName);
    }




//    private ApiClient initializeK8Client() {
//        String rgName = "ws-test-aks-rg";
//        String clusterName = "ws-test-aks-cluster-1";
//        AzureResourceManager azureResourceManager = getAzureResourceManager("cb51e8d1-519c-4e18-9b2f-28d53e6badd1", "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ",
//                "f875ebf8-f5f0-4915-a2c9-4442e0118fd2", "4769af8e-ca3d-448d-bd1a-80e03ed94158");
//        KubernetesCluster cluster = azureResourceManager
//                .kubernetesClusters()
//                .getByResourceGroup(rgName, clusterName);
//        String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
//        String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);
//
//        String serverUrl = extractedValues[0];
//        String bearerToken = extractedValues[1];
//        log.info("bearerToken: {}", bearerToken);
//
//        ApiClient client = new ApiClient();
//        client.setBasePath(serverUrl);
//        client.setVerifyingSsl(false);
//
//        client.setApiKey(bearerToken);
//        client.setApiKeyPrefix("Authorization");
//
//        io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
//        return client;
//    }
//
//
//
//
//
//
//
//    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
//        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
//    }
//
//
//    private static String[] extractServerAndTokenFromKubeConfigYAML(String config) {
//        String[] result = new String[2];
//
//        String serverPrefix = "server: ";
//        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
//        int serverEnd = config.indexOf("\n", serverStart);
//        result[0] = config.substring(serverStart, serverEnd).trim();
//
//        String tokenPrefix = "token: ";
//        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
//        int tokenEnd = config.indexOf("\n", tokenStart);
//        result[1] = config.substring(tokenStart, tokenEnd).trim();
//
//        return result;
//    }
//
//
//
//




    /**
     * Retrieve signed certificate (with retries)
     */
    public String getSignedCertificate(String csrName, int maxRetries) throws Exception {
        log.info("Retrieving signed certificate for CSR: {}", csrName);

        CertificatesV1Api api = new CertificatesV1Api(apiClient);

        for (int i = 0; i < maxRetries; i++) {
            V1CertificateSigningRequest csr = api.readCertificateSigningRequest(csrName).execute();

            if (csr.getStatus() != null && csr.getStatus().getCertificate() != null) {
                log.info("Certificate retrieved: {}", csrName);

                // ✅ CORRECT: Convert byte array to base64 string
                byte[] certBytes = csr.getStatus().getCertificate();
                String certBase64 = Base64.getEncoder().encodeToString(certBytes);

                log.info("Certificate (base64): {}", certBase64);
                return certBase64;
            }

            log.debug("Certificate not yet signed, retrying... ({}/{})", i + 1, maxRetries);
            Thread.sleep(1000);
        }

        throw new Exception("Certificate signing timeout for CSR: " + csrName);
    }



    /**
     * Delete CSR (cleanup)
     */
    public void deleteCertificateSigningRequest(String csrName) throws Exception {
        log.info("Deleting CSR: {}", csrName);
        CertificatesV1Api api = new CertificatesV1Api(apiClient);
        api.deleteCertificateSigningRequest(csrName).execute();
        log.info("CSR deleted: {}", csrName);
    }

    private V1CertificateSigningRequest buildCSRObject(
            String csrName,
            String csrBase64,
            Integer expirationSeconds,
            List<String> groups) {

        V1CertificateSigningRequest csr = new V1CertificateSigningRequest();
        csr.setApiVersion("certificates.k8s.io/v1");
        csr.setKind("CertificateSigningRequest");

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(csrName);
        csr.setMetadata(metadata);


        byte[] csrBytes = Base64.getDecoder().decode(csrBase64);


        V1CertificateSigningRequestSpec spec = new V1CertificateSigningRequestSpec();
        spec.setRequest(csrBytes);
        spec.setSignerName(certificateConfig.getSignerName());
        spec.setExpirationSeconds(expirationSeconds);
        spec.setUsages(Arrays.asList("digital signature", "key encipherment", "client auth"));

        if (groups != null && !groups.isEmpty()) {
            spec.setGroups(groups);
        }

        csr.setSpec(spec);
        return csr;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class CertificateSigningRequestData {
        private String csrName;
        private String csrPem;
        private String csrBase64;
        private String privateKeyPem;
        private String privateKeyBase64;
        private String userName;
        private List<String> groups;
        private Integer expirationSeconds;
        private Long createdTimestamp;
        private Long expirationTimestamp;
    }
}
