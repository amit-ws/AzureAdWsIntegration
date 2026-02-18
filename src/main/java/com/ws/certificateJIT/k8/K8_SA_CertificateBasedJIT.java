//package com.ws.certificateJIT.k8;
//
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import io.kubernetes.client.custom.V1Patch;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.ApiException;
//import io.kubernetes.client.openapi.apis.CertificatesV1Api;
//import io.kubernetes.client.openapi.apis.CoreV1Api;
//import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
//import io.kubernetes.client.openapi.models.*;
//import lombok.AccessLevel;
//import lombok.experimental.FieldDefaults;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Service;
//
//import java.security.KeyPair;
//import java.security.PrivateKey;
//import java.time.Duration;
//import java.time.Instant;
//import java.time.ZoneOffset;
//import java.time.format.DateTimeFormatter;
//import java.util.*;
//import java.util.concurrent.Executors;
//import java.util.concurrent.ScheduledExecutorService;
//import java.util.concurrent.TimeUnit;
//
//@Service
//@Slf4j
//@FieldDefaults(level = AccessLevel.PRIVATE)
//public class K8_SA_CertificateBasedJIT {
//
//    Map<String, PrivateKey> csrPrivateKeyMap = new HashMap<>();
//
//    final String CLUSTER_NAME = "ws-test-aks-cluster-1";
//    final String CLUSTER_SERVER = "https://ws-test-aks-cluster-1-dns-8t33e8yw.hcp.eastus.azmk8s.io:443";
//    final String caCertBase64 = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUU2VENDQXRHZ0F3SUJBZ0lSQU9JbVRxVDFyZVdKQVlINUNlUVc0WkF3RFFZSktvWklodmNOQVFFTEJRQXcKRFRFTE1Ba0dBMVVFQXhNQ1kyRXdJQmNOTWpVd01qSXdNVEl4T0RRMFdoZ1BNakExTlRBeU1qQXhNakk0TkRSYQpNQTB4Q3pBSkJnTlZCQU1UQW1OaE1JSUNJakFOQmdrcWhraUc5dzBCQVFFRkFBT0NBZzhBTUlJQ0NnS0NBZ0VBCjNIMklTRVVZT1lJcmdoOHBlV3MrZHF2U2J2K1A4dThLWTFDZ2dkTWx2dzViTXMvaU90MXByK2U4Rm9vMUkzMDUKYnBnKzFqQ2NvTUJsNTRxdVc1MGFsNVFhSE1QblZCM0xrZ2g4M01teU5ITWVBWHpudFBTMEI2RllKc25QaldZVgo4U2tNYjZIbFB5RnpKNk5oTHZXQ2t3d3BVOTFFZ3ZNZUJvTVZ0dGhUYmZHamM1eFNJdEJLaXpBeFhqWENlcVZzCjY3ZGlpdC9YNDhESDNnM05MNk5kQmVpdkluYktMUVJWdUg3MzlHbUFsQ2drSU0yYjFzNnZaK3ZDaHpoZFVLMVIKV2IxVER3UHJidXhxZUE0YVB0dVV1OC9NNCtVRDhEeC8vNStKbHRnengyYUFpQXN5OU9YVGlNUk9HUnpOcCs1dQovbzZZdzJveFVwRGVJOUVUZ214ZVp4OTdNR3g5WU15WDY4VFFiTUhwOUpEV0J2dkcwMHlLeDFqMWJQVWRBaGF3CjlBbjFlT1NZcmdOdHdnRStBb1JDcXJhV2dQUDZyZ3Q5bWhqZE44UGpxNElRQnpHaUhObGxrUk5yTStwSEllTFQKNDZWVC81T2F3d2pHUXoyWFFGcFdVa0RNQSs1L0RsS1QwcXFtN0l0SHQzTVpWWXBwWWdNQWtkc25PTmRHbkhHdgowQkZaUnpWK3lHNHFjUWZDMTFzc2hmSzNPRUhsZERCR2grbzJqRWVXTjZTdHQrL3J3TU1NWlVxY3NRUW9hNSswCmtFT0cyenpSWUgycnM1aGVHMzIwbVVQT3lzRXNsdGFSUUs5bEVjakROdHF2eVFXUjlubjU0VjhLOThLQWJCZTYKNkhkYWM2M0VhQy8vME5zMm10R1Nhd1lnSFJXYkNLa2VQQlVpdjZxeStuVUNBd0VBQWFOQ01FQXdEZ1lEVlIwUApBUUgvQkFRREFnS2tNQThHQTFVZEV3RUIvd1FGTUFNQkFmOHdIUVlEVlIwT0JCWUVGUHFtVDR6aHVjQVAzSnBzCkNZTXcrUXcxU09wZE1BMEdDU3FHU0liM0RRRUJDd1VBQTRJQ0FRQ3dNR3JQWlVYa1kzUmcwWDZuWThZSW5JWGsKTGZKU1FERkJYUDBOTk11RVRJcUczeWwyRGJldXNmWGkzaHZaZ0pmS01aYXNueDVoNmIxUFFOMU9RVHpSQUdTQgpIOUk0bnMyNVluUUMvd0puT2JuVHhSUHI0YzBlUURnMzRCam4weUhSbkQvT0o5cHJrb2F5eUxUcC9ySXZ2QU1pCng4c1lNVjM5cEVXOEJlWGhScGJnUlRQdDhlZ2FncWVRZ2E2bVFRYmgyT3prV0VzRjIvS09WbUpUVThmWDVWQkwKWXNJKzJGeFp3UXJ2YmtLQmlQdDJyWTl3U2lKWW16dEVxOVB3WWYxVkVWN1hGMzV5azBmbFJxVG5KaWtDZVhldwp0K3BMR2hDa25oa0FiR2pNUlo0d1RPb2dyQnA0T1hTNzB3TUpwcWVMQXJIZXBtMWFuRGRncDhjUmhmVDFsalJrClBvU0x1TStHK0IxMXBTQU1TSnBDV1B0c1FGU3ZhWmo3MXFQSXZKdU9zNHV4QXhMUjJPTlM1UFBwUEdGT1I2THoKRDdQbmhEbS9oTVFsYVQ2L1ZpOFBJbHVhSkp3dzdQOGlHaWtuRE80MjA3N0FjcElTNzJiL2hzbnFRRlMzOERGSAp6anFGTVB0N3MreEEzOHpNUFQ0Wmt6UjZwdXU2RG9laXJER2JCTnpMUUdGdHBBSDVpdUNJUmJjSmNHUnhmV1EwCkRSbXpHcEpJT3U2YS9XOFBNSkNFNWFoeUFSelpEWTFsK0dNaHNFU0lMbjJnZWdVTTVGRVVCWmxUeWJONUdsdUkKRlU4cTUwZDFRakF3Ky9SRjFpanZuV2hPUElmU1gzMm5VNnRGcHRJeVo1UktyZDRwNWNHMmNxQ3QyUExqNWtwVwp1WW1hYmJ6RmJlY3JZcFlvSnc9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg==";
//
//    final ObjectMapper objectMapper = new ObjectMapper();
//    final ApiClient apiClient;
//    final CertificateSigningService certificateSigningService;
//    final CertificateConfig certificateConfig;
//
//    final JitAccessGrantRepository jitAccessGrantRepository;
//
//    private String namespace;
//    private long duration;
//
//    @Autowired
//    public K8_SA_CertificateBasedJIT(ApiClient apiClient, CertificateSigningService certificateSigningService, CertificateConfig certificateConfig, JitAccessGrantRepository jitAccessGrantRepository) {
//        this.apiClient = apiClient;
//        this.certificateSigningService = certificateSigningService;
//        this.certificateConfig = certificateConfig;
//        this.jitAccessGrantRepository = jitAccessGrantRepository;
//    }
//
//
//    public String createServiceAccountWithJit(String saName, String targetResource, String namespace, long duration) {
//        try {
//            this.namespace = namespace;
//            this.duration = duration;
//
//            CoreV1Api coreV1Api = new CoreV1Api(apiClient);
//
//            V1ServiceAccount serviceAccount = new V1ServiceAccount()
//                    .apiVersion("v1")
//                    .kind("ServiceAccount")
//                    .metadata(new V1ObjectMeta()
//                            .name(saName)
//                            .namespace(this.namespace)
//                            .labels(Map.of(
//                                    "app.kubernetes.io/enabled", "true",
//                                    "app.kubernetes.io/name", "jit-serviceaccount",
//                                    "app.kubernetes.io/instance", saName
//                            )));
//
//            coreV1Api.createNamespacedServiceAccount(this.namespace, serviceAccount).execute();
//            log.info("Service Account created: {}/{}", this.namespace, saName);
//
//
//            return createJitAccessForServiceAccount(saName, targetResource);
//
//        } catch (ApiException e) {
//            log.error("Failed to create Service Account", e);
//            throw new RuntimeException("Failed to create Service Account: " + e.getMessage(), e);
//        }
//    }
//
//    public String createJitAccessForServiceAccount(String saName, String targetResource) {
//        try {
//
//            final String jitIdentity = "jit-" + saName + "-" + UUID.randomUUID();
//            log.info("Creating JIT access for SA: {}, Identity: {}", saName, jitIdentity);
//
//            String csrName = createCertificateSigningRequest(jitIdentity, saName, this.duration);
//
//            approveCertificateSigningRequest(csrName);
//
//            String kubeconfig = generateKubeconfig(jitIdentity, this.duration);
//
//            String encodedKubeconfig = Base64.getEncoder().encodeToString(kubeconfig.getBytes());
//            log.info("encodedKubeconfig: {}", encodedKubeconfig);
//            String secretName = createSecret(saName, encodedKubeconfig.getBytes());
//
//            String roleName = createRole(saName, targetResource);
//
//            String bindingName = createRoleBinding(saName, roleName);
//
//            long expiresAt = System.currentTimeMillis() + (this.duration * 1000);
//            JitAccessGrant grant = JitAccessGrant.builder()
//                    .saName(saName)
//                    .namespace(namespace)
//                    .jitIdentity(jitIdentity)
//                    .targetResource(targetResource)
//                    .expiresAt(Instant.ofEpochMilli(expiresAt))
//                    .csrName(csrName)
//                    .secretName(secretName)
//                    .roleName(roleName)
//                    .bindingName(bindingName)
//                    .createdAt(new Date())
//                    .build();
//
//            jitAccessGrantRepository.save(grant);
//
//            log.info("JIT access created successfully for SA: {}. Grant ID: {}", saName, grant.getId());
//            return kubeconfig;
//
//        } catch (Exception e) {
//            log.error("Failed to create JIT access for SA: {}", saName, e);
//            throw new RuntimeException("Failed to create JIT access: " + e.getMessage(), e);
//        }
//    }
//
//
//    public String createCertificateSigningRequest(String csrName, String saName, long expirationSeconds) {
//        try {
//            // Generate key pair and CSR
//            KeyPair keyPair = CertificateUtil.generateKeyPair();
//            String csrPem = CertificateUtil.generateCSR(keyPair, saName, new ArrayList<>());
//            String csrBase64 = EncodingUtil.compactBase64(EncodingUtil.encodeBase64(csrPem));
//
//
//            // Store private key for later retrieval
//            csrPrivateKeyMap.put(csrName, keyPair.getPrivate());
//
//
//            V1CertificateSigningRequest csrObject = buildCSRObject(
//                    csrName,
//                    csrBase64,
//                    (int) expirationSeconds,
//                    new ArrayList<>()
//            );
//
//            CertificatesV1Api api = new CertificatesV1Api(apiClient);
//            api.createCertificateSigningRequest(csrObject).execute();
//
//            log.info("Certificate Signing Request created: {}", csrName);
//            return csrObject.getMetadata().getName();
//        } catch (Exception e) {
//            log.error("Failed to create CSR: {}", csrName, e);
//            throw new RuntimeException("Failed to create CSR: " + e.getMessage(), e);
//        }
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
//        metadata.setLabels(Map.of(
//                "app.kubernetes.io/name", "jit-csr",
//                "app.kubernetes.io/instance", csrName
//        ));
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
//    public void approveCertificateSigningRequest(String csrName) {
//        try {
//            CertificatesV1Api certificatesApi = new CertificatesV1Api(apiClient);
//
//            V1CertificateSigningRequest csr = certificatesApi.readCertificateSigningRequest(csrName).execute();
//
//            V1CertificateSigningRequestCondition approvalCondition =
//                    new V1CertificateSigningRequestCondition()
//                            .type("Approved")
//                            .status("True")
//                            .reason("ApprovedByJitController")
//                            .message("Approved by JIT Access Controller");
//
//            if (csr.getStatus().getConditions() == null) {
//                csr.getStatus().setConditions(new ArrayList<>());
//            }
//
//            csr.getStatus().getConditions().add(approvalCondition);
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
//
//            okhttp3.MediaType mediaType = okhttp3.MediaType.parse("application/strategic-merge-patch+json");
//            okhttp3.RequestBody body = okhttp3.RequestBody.create(patchJson, mediaType);
//
//
//            okhttp3.Request request = new okhttp3.Request.Builder()
//                    .url(url)
//                    .patch(body)
//                    .addHeader("Content-Type", "application/strategic-merge-patch+json")
//                    .addHeader("Accept", "application/json")
//                    .addHeader("Authorization", "Bearer " + "2m1cdratgzh42n8k2n7rdd5ovazvn4ymgvdeb5bk2vjp94o5wqr8vwgfr0m863f8c0lwzr7rtmb5dy8dzwafp09kj4jrcqt5n49v4qsc9sls018c2nt20u9pyoqahqhi")  // ✅ Add auth header
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
//            log.info("Certificate Signing Request approved: {}", csrName);
//
//            waitForCertificateIssuance(csrName, 30);
//
//        } catch (Exception e) {
//            log.error("Failed to approve CSR: {}", csrName, e);
//            throw new RuntimeException("Failed to approve CSR: " + e.getMessage(), e);
//        }
//    }
//
//
//    private String createSecret(String saName, byte[] encodedkubeconfig) {
//        try {
//            CoreV1Api coreV1Api = new CoreV1Api(apiClient);
//
//            String secretName = "jit-kubeconfig-" + saName;
//
//            V1Secret secret = new V1Secret()
//                    .apiVersion("v1")
//                    .kind("Secret")
//                    .metadata(new V1ObjectMeta()
//                            .name(secretName)
//                            .namespace(namespace)
//                            .labels(Map.of(
//                                    "app.kubernetes.io/name", "jit-kubeconfig",
//                                    "app.kubernetes.io/instance", saName
//                            )))
//                    .type("Opaque")
//                    .data(Map.of(
//                            "kubeconfig", encodedkubeconfig));
//
//            coreV1Api.createNamespacedSecret(namespace, secret).execute();
//            log.info("Secret created: {}/{}", namespace, secretName);
//            return secretName;
//        } catch (Exception e) {
//            log.error("Failed to create Secret", e);
//            throw new RuntimeException("Failed to create Secret: " + e.getMessage(), e);
//        }
//    }
//
//
//    public String createRole(String saName, String targetResource) {
//        try {
//            RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api(apiClient);
//
//            String roleName = "jit-role-" + saName;
//
//            // Parse target resource: "secret:my-secret" or "pod:my-pod"
//            String[] parts = targetResource.split(":");
//            String resourceType = parts[0];
//            String resourceName = parts.length > 1 ? parts[1] : "*";
//
//            // Create Role
//            V1Role role = new V1Role()
//                    .apiVersion("rbac.authorization.k8s.io/v1")
//                    .kind("Role")
//                    .metadata(new V1ObjectMeta()
//                            .name(roleName)
//                            .namespace(namespace)
//                            .labels(Map.of(
//                                    "app.kubernetes.io/name", "jit-role",
//                                    "app.kubernetes.io/instance", saName
//                            )))
//                    .rules(Arrays.asList(
//                            new V1PolicyRule()
//                                    .apiGroups(Arrays.asList(""))
//                                    .resources(Arrays.asList(resourceType))
//                                    .verbs(Arrays.asList("get", "list"))
//                                    .resourceNames(Arrays.asList(resourceName))
//                    ));
//
//            V1Role createdRole = rbacApi.createNamespacedRole(namespace, role).execute();
//            log.info("Role created: {}/{}", namespace, roleName);
//            return roleName;
//        } catch (Exception e) {
//            log.error("Failed to create Role", e);
//            throw new RuntimeException("Failed to create Role: " + e.getMessage(), e);
//        }
//    }
//
//
//    public String createRoleBinding(String saName, String roleName) {
//        try {
//            RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api(apiClient);
//
//            String bindingName = "jit-binding-" + saName;
//
//            V1RoleBinding roleBinding = new V1RoleBinding()
//                    .apiVersion("rbac.authorization.k8s.io/v1")
//                    .kind("RoleBinding")
//                    .metadata(new V1ObjectMeta()
//                            .name(bindingName)
//                            .namespace(namespace)
//                            .labels(Map.of(
//                                    "app.kubernetes.io/name", "jit-binding",
//                                    "app.kubernetes.io/instance", saName
//                            )))
//                    .roleRef(new V1RoleRef()
//                            .apiGroup("rbac.authorization.k8s.io")
//                            .kind("Role")
//                            .name(roleName))
//                    .subjects(Arrays.asList(
//                            new RbacV1Subject()
//                                    .kind("ServiceAccount")
//                                    .name(saName)
//                                    .namespace(namespace)
//                    ));
//
//            rbacApi.createNamespacedRoleBinding(namespace, roleBinding).execute();
//            log.info("RoleBinding created: {}/{}", namespace, bindingName);
//            return bindingName;
//        } catch (Exception e) {
//            log.error("Failed to create RoleBinding", e);
//            throw new RuntimeException("Failed to create RoleBinding: " + e.getMessage(), e);
//        }
//    }
//
//
//    private void waitForCertificateIssuance(String csrName, int timeoutSeconds) throws InterruptedException {
//        CertificatesV1Api certificatesApi = new CertificatesV1Api(apiClient);
//        long startTime = System.currentTimeMillis();
//        long timeoutMs = timeoutSeconds * 1000L;
//
//        while (System.currentTimeMillis() - startTime < timeoutMs) {
//            try {
//                V1CertificateSigningRequest csr = certificatesApi.readCertificateSigningRequest(csrName).execute();
//
//                if (csr.getStatus() != null && csr.getStatus().getCertificate() != null) {
//                    log.info("Certificate issued for CSR: {}", csrName);
//                    return;
//                }
//
//                Thread.sleep(1000); // Wait 1 second before retrying
//
//            } catch (ApiException e) {
//                log.warn("Error checking CSR status, retrying...", e);
//                Thread.sleep(1000);
//            }
//        }
//
//        throw new RuntimeException("Timeout waiting for certificate issuance for CSR: " + csrName);
//    }
//
//    /**
//     * Convert Kubernetes object to JSON patch (simple implementation)
//     */
//    private String kubernetesObjectToJsonPatch(Object obj) {
//        try {
//            ObjectMapper mapper = new ObjectMapper();
//            return mapper.writeValueAsString(obj);
//        } catch (Exception e) {
//            log.error("Failed to convert object to JSON", e);
//            throw new RuntimeException("Failed to convert object to JSON", e);
//        }
//    }
//
//
//    public String generateKubeconfig(String jitIdentity, long expirationSeconds) {
//        try {
//            // Fetch the CSR to get the certificate
//            CertificatesV1Api certificatesApi = new CertificatesV1Api(apiClient);
//            V1CertificateSigningRequest csr = certificatesApi.readCertificateSigningRequest(jitIdentity).execute();
//
//            if (csr.getStatus() == null || csr.getStatus().getCertificate() == null) {
//                throw new RuntimeException("Certificate not yet issued for CSR: " + jitIdentity);
//            }
//
//            // Decode the certificate
//            String certificateBase64 = new String(csr.getStatus().getCertificate());
//
//            // Get the private key (stored during CSR creation)
//            String privateKeyPem = retrievePrivateKey(jitIdentity);
//            String privateKeyBase64 = Base64.getEncoder().encodeToString(privateKeyPem.getBytes());
//
//            // Calculate expiration time
//            long expirationTimestamp = System.currentTimeMillis() + (expirationSeconds * 1000);
//            String expirationTime = Instant.ofEpochMilli(expirationTimestamp)
//                    .atZone(ZoneOffset.UTC)
//                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//
//            // Build kubeconfig
//            String kubeconfig = String.format("""
//                            apiVersion: v1
//                            kind: Config
//                            clusters:
//                            - name: %s
//                              cluster:
//                                server: %s
//                                certificate-authority-data: %s
//                            contexts:
//                            - name: %s@%s
//                              context:
//                                cluster: %s
//                                user: %s
//                            current-context: %s@%s
//                            users:
//                            - name: %s
//                              user:
//                                client-certificate-data: %s
//                                client-key-data: %s
//                            """,
//                    CLUSTER_NAME,
//                    CLUSTER_SERVER,
//                    caCertBase64,
//                    jitIdentity,
//                    CLUSTER_NAME,
//                    CLUSTER_NAME,
//                    jitIdentity,
//                    jitIdentity,
//                    CLUSTER_NAME,
//                    jitIdentity,
//                    certificateBase64,
//                    privateKeyBase64
//            );
//
//            log.info("Kubeconfig generated for JIT identity: {}", jitIdentity);
//            return kubeconfig;
//
//        } catch (ApiException e) {
//            log.error("Failed to generate kubeconfig for JIT identity: {}", jitIdentity, e);
//            throw new RuntimeException("Failed to generate kubeconfig: " + e.getMessage(), e);
//        }
//    }
//
//
//    private String retrievePrivateKey(String csrName) {
//        PrivateKey privateKey = csrPrivateKeyMap.get(csrName);
//        if (privateKey == null) {
//            throw new RuntimeException("Private key not found for CSR: " + csrName);
//        }
//
//        return encodePrivateKeyToPem(privateKey);
//    }
//
//
//    private String encodePrivateKeyToPem(PrivateKey privateKey) {
//        try {
//            byte[] encodedKey = privateKey.getEncoded();
//            String encodedString = Base64.getEncoder().encodeToString(encodedKey);
//
//            return "-----BEGIN RSA PRIVATE KEY-----\n" +
//                    encodedString.replaceAll("(.{64})", "$1\n") +
//                    "\n-----END RSA PRIVATE KEY-----";
//
//        } catch (Exception e) {
//            log.error("Failed to encode private key to PEM", e);
//            throw new RuntimeException("Failed to encode private key: " + e.getMessage(), e);
//        }
//    }
//
//
////    ROTATION LOGIC GOES HERE ---->
//
//    // Every 5 minutes
//    @Scheduled(fixedDelay = 300000)
//    public void rotateTheCertificate() {
//
//        try {
//            List<JitAccessGrant> expiringGrants = jitAccessGrantRepository.findExpiringAfter(Instant.now().plus(Duration.ofMinutes(10)));
//            log.info("Found {} grants expiring soon. Starting rotation...", expiringGrants.size());
//
//            for (JitAccessGrant grant : expiringGrants) {
//                rotateIndividualGrant(grant);
//            }
//
//            log.info("Certificate rotation completed successfully");
//
//        } catch (Exception e) {
//            log.error("Error during certificate rotation", e);
//        }
//    }
//
//    private void rotateIndividualGrant(JitAccessGrant grant) {
//        try {
//            String saName = grant.getSaName();
//            String namespace = grant.getNamespace();
//            String oldCsrName = grant.getCsrName();
//
//            log.info("Rotating certificate for SA: {}/{}", namespace, saName);
//
//            String newJitIdentity = "jit-" + saName + "-" + UUID.randomUUID();
//            String newCsrName = createCertificateSigningRequest(newJitIdentity, saName, 3600);
//
//            approveCertificateSigningRequest(newCsrName);
//
//            String newKubeconfig = generateKubeconfig(newJitIdentity, 3600);
//
//            updateSecretWithNewKubeconfig(namespace, grant.getSecretName(), newKubeconfig);
//
//            scheduleOldCsrDeletion(oldCsrName, 10);
//
//            long newExpiresAt = System.currentTimeMillis() + (3600 * 1000);
//            grant.setExpiresAt(Instant.ofEpochMilli(newExpiresAt));
//            grant.setCsrName(newCsrName);
//            grant.setJitIdentity(newJitIdentity);
//            grant.setRotatedAt(Instant.now());
//            jitAccessGrantRepository.save(grant);
//
//            log.info("Certificate rotated successfully for SA: {}/{}", namespace, saName);
//
//        } catch (Exception e) {
//            log.error("Failed to rotate certificate for SA: {}", grant.getSaName(), e);
//        }
//    }
//
//
//    private void updateSecretWithNewKubeconfig(String namespace, String secretName, String newKubeconfig) {
//        try {
//            CoreV1Api coreV1Api = new CoreV1Api(apiClient);
//
//            V1Secret secret = coreV1Api.readNamespacedSecret(secretName, namespace).execute();
//
//            if (secret.getData().containsKey("kubeconfig")) {
//                secret.getData().put("kubeconfig-old", secret.getData().get("kubeconfig"));
//            }
//
//            String encodedNewKubeconfig = Base64.getEncoder().encodeToString(newKubeconfig.getBytes());
//            secret.getData().put("kubeconfig", encodedNewKubeconfig.getBytes());
//
//            coreV1Api.patchNamespacedSecret(secretName, namespace, new V1Patch(objectToJsonPatch(secret)));
//
//            log.info("Secret updated with new kubeconfig: {}/{}", namespace, secretName);
//
//        } catch (ApiException e) {
//            log.error("Failed to update Secret: {}/{}", namespace, secretName, e);
//            throw new RuntimeException("Failed to update Secret", e);
//        }
//    }
//
//
//    private void scheduleOldCsrDeletion(String csrName, int delayMinutes) {
//        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
//
//        scheduler.schedule(() -> {
//            try {
//                CertificatesV1Api certificatesApi = new CertificatesV1Api(apiClient);
//                certificatesApi.deleteCertificateSigningRequest(csrName).execute();
//
//                log.info("Old CSR deleted: {}", csrName);
//
//            } catch (ApiException e) {
//                log.error("Failed to delete old CSR: {}", csrName, e);
//            }
//        }, delayMinutes, TimeUnit.MINUTES);
//    }
//
//
//    private String objectToJsonPatch(Object obj) {
//        try {
//            ObjectMapper mapper = new ObjectMapper();
//            return mapper.writeValueAsString(obj);
//        } catch (Exception e) {
//            throw new RuntimeException("Failed to convert object to JSON", e);
//        }
//    }
//
//
//    // Every 10 minutes
//    @Scheduled(fixedDelay = 600000)
//    public void cleanupOldKubeconfigs() {
//        try {
//            Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
//            List<JitAccessGrant> grantsWithRotationHistory = jitAccessGrantRepository.findAllRotatedBefore(cutoff);
//
//            for (JitAccessGrant grant : grantsWithRotationHistory) {
//                cleanupOldKubeconfigForGrant(grant);
//            }
//
//        } catch (Exception e) {
//            log.error("Error during old kubeconfig cleanup", e);
//        }
//    }
//
//
//    private void cleanupOldKubeconfigForGrant(JitAccessGrant grant) {
//        try {
//            CoreV1Api coreV1Api = new CoreV1Api(apiClient);
//            String namespace = grant.getNamespace();
//            String secretName = grant.getSecretName();
//
//            V1Secret secret = coreV1Api.readNamespacedSecret(secretName, namespace).execute();
//
//            // Remove old kubeconfig if it exists
//            if (secret.getData().containsKey("kubeconfig-old")) {
//                secret.getData().remove("kubeconfig-old");
//
//                coreV1Api.patchNamespacedSecret(
//                        secretName,
//                        namespace,
//                        new V1Patch(objectToJsonPatch(secret))
//                );
//
//                log.info("Cleaned up old kubeconfig from Secret: {}/{}", namespace, secretName);
//            }
//
//        } catch (ApiException e) {
//            log.error("Failed to cleanup old kubeconfig for grant: {}", grant.getId(), e);
//        }
//    }
//
//
//}
//
//
//
//
//
//
//
//
//
//
//
