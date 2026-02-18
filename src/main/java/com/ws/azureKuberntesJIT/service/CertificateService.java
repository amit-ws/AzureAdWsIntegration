//package com.ws.azureKuberntesJIT.service;
//
//import com.azure.resourcemanager.AzureResourceManager;
//import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
//import com.ws.configuration.AzureAuthConfigurationFactory;
//import io.kubernetes.client.openapi.ApiClient;
//import io.kubernetes.client.openapi.Configuration;
//import io.kubernetes.client.openapi.apis.CertificatesV1Api;
//import io.kubernetes.client.openapi.apis.RbacAuthorizationV1Api;
//import io.kubernetes.client.openapi.auth.ApiKeyAuth;
//import io.kubernetes.client.openapi.models.*;
//import io.kubernetes.client.util.Config;
//import lombok.extern.slf4j.Slf4j;
//import okhttp3.*;
//import org.bouncycastle.asn1.x500.X500Name;
//import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
//import org.bouncycastle.operator.ContentSigner;
//import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
//import org.bouncycastle.pkcs.PKCS10CertificationRequest;
//import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
//import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.io.StringWriter;
//import java.nio.charset.StandardCharsets;
//import java.security.KeyPair;
//import java.security.KeyPairGenerator;
//import java.security.NoSuchAlgorithmException;
//import java.security.Security;
//import java.time.Instant;
//import java.util.Base64;
//import java.util.Collections;
//import java.util.List;
//import java.util.UUID;
//
//@Service
//@Slf4j
//public class CertificateService {
//    final String clientId = "cb51e8d1-519c-4e18-9b2f-28d53e6badd1";
//    final String clientSecret = "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ";
//    final String tenantId = "f875ebf8-f5f0-4915-a2c9-4442e0118fd2";
//    final String subscriptionId = "4769af8e-ca3d-448d-bd1a-80e03ed94158";
//
//
//    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
//
//    @Autowired
//    public CertificateService(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
//        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
//    }
//
//
//    public ApiClient initializeK8Client() {
//        String rgName = "ws-test-aks-rg";
//        String clusterName = "ws-test-aks-cluster-1";
//        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
//        KubernetesCluster cluster = azureResourceManager
//                .kubernetesClusters()
//                .getByResourceGroup(rgName, clusterName);
//        String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
//        String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);
//
//        ApiClient client = Config.fromToken(extractedValues[0], extractedValues[1]);
//        client.setVerifyingSsl(false);
//        Configuration.setDefaultApiClient(client);
//        return client;
//    }
//
//
//    public static String[] extractServerAndTokenFromKubeConfigYAML(String config) {
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
//    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
//        log.info("clientSecret: " + clientSecret);
//        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
//    }
//
//
//    public String createTemporaryKubeconfig(String username,
//                                            String namespace,
//                                            String resourceType,
//                                            List<String> verbs,
//                                            List<String> resourceNames, // optional: resourceNames to scope Role
//                                            int ttlSeconds) throws Exception {
//
//        final String group = "jit-access";
//        final String roleName = UUID.randomUUID().toString();
//        final ApiClient apiClient = initializeK8Client();
//
//        // 1) Ensure Role exists
//        ensureRole(namespace, resourceType, roleName, verbs, resourceNames);
//
//        // 2) Ensure RoleBinding exists binding Role to the group
//        ensureRoleBinding(namespace, roleName, group);
//
//        // 3) Generate keypair & CSR PEM
//        // Usage
//        KeyPair keyPair = generateRsaKeyPair();
//        String csrPem = generateCsrPem(username, group, keyPair);
//
//// Convert PEM string to bytes
//        byte[] csrPemBytes = csrPem.getBytes(StandardCharsets.UTF_8);
//
//// Create Kubernetes CSR object
//        CertificatesV1Api certApi = new CertificatesV1Api(apiClient);
//
//// Sanitize and truncate username
//        String safeUsername = username.toLowerCase().replaceAll("[^a-z0-9-]", "-");
//        if (safeUsername.length() > 50) {
//            safeUsername = safeUsername.substring(0, 50);
//        }
//
//// Generate a fully unique CSR name
//        String csrName = "jit-csr-" + safeUsername + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
//
//// Create Kubernetes CSR object
//        V1CertificateSigningRequest csrResource = new V1CertificateSigningRequest()
//                .metadata(new V1ObjectMeta().name(csrName))
//                .spec(new V1CertificateSigningRequestSpec()
//                        .request(csrPemBytes) // pass PEM bytes directly
//                        .signerName("kubernetes.io/kube-apiserver-client")
//                        .addUsagesItem("client auth")
//                        .expirationSeconds(ttlSeconds)
//                );
//
//
//// Create CSR in Kubernetes
//        V1CertificateSigningRequest created = certApi.createCertificateSigningRequest(csrResource).execute();
//
////        // Optional: Check created CSR metadata
////        System.out.println("CSR created: " + created.getMetadata().getName());
////        System.out.println("CSR UID: " + created.getMetadata().getUid());
//
//        // 5) Approve the CSR (patch approval)
//        // Use the returned object to guarantee correct identity
//        String createdName = created.getMetadata().getName();
//        String createdUid = created.getMetadata().getUid();
//
//        log.info("created...");
//
//        // 5) Approve the CSR using the created object's metadata
//        approveCsr(apiClient, createdName, "Approved by JIT-Controller");
//
//        log.info("2");
//
//
//        // 6) Poll for certificate in status
//        String issuedCertPem = waitForIssuedCert(certApi, csrName, 30, 2000);
//
//        log.info("3");
//
//
//        if (issuedCertPem == null) {
//            throw new RuntimeException("Timed out waiting for issued certificate for CSR " + csrName);
//        }
//
//        // 7) Build kubeconfig string with cluster info from current ApiClient config
//        String kubeconfig = KubeconfigBuilder.buildKubeconfigFromApiClient(initializeK8Client(), username, keyPair, issuedCertPem);
//        log.info("4");
//        return kubeconfig;
//    }
//
//    private byte[] generateCsrDerBytes(String username, String group, KeyPair keyPair) throws Exception {
//        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
//
//        X500Name subject = new X500Name("CN=" + username + (group != null ? ",O=" + group : ""));
//        PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
//        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
//        PKCS10CertificationRequest csr = p10Builder.build(signer);
//
//        return csr.getEncoded(); // DER bytes
//    }
//
//
////    public static String generateCSR(String commonName) throws Exception {
////        // Generate a key pair
////        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
////        keyGen.initialize(2048);
////        KeyPair keyPair = keyGen.generateKeyPair();
////
////        // Build CSR
////        String csrPem;
////        {
////            var builder = new org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder(
////                    new org.bouncycastle.asn1.x500.X500Name("CN=" + commonName),
////                    org.bouncycastle.asn1.pkcs.SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded())
////            );
////            var signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
////            var csr = builder.build(signer);
////            csrPem = "-----BEGIN CERTIFICATE REQUEST-----\n" +
////                    Base64.getEncoder().encodeToString(csr.getEncoded()) +
////                    "\n-----END CERTIFICATE REQUEST-----";
////        }
////
////        // Kubernetes wants base64-encoded CSR
////        return Base64.getEncoder().encodeToString(csrPem.getBytes());
////    }
//
//
//    private KeyPair generateRsaKeyPair() throws NoSuchAlgorithmException {
//        KeyPairGenerator kpGen = KeyPairGenerator.getInstance("RSA");
//        kpGen.initialize(2048);
//        return kpGen.generateKeyPair();
//    }
//
//    private String generateCsrPem(String username, String group, KeyPair keyPair) throws Exception {
//        // Use BouncyCastle to create CSR
//        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
//
//        X500Name subject = new X500Name("CN=" + username + (group != null ? ",O=" + group : ""));
//        PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());
//        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
//        PKCS10CertificationRequest csr = p10Builder.build(signer);
//
//        // Convert to PEM
//        StringWriter sw = new StringWriter();
//        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
//            pemWriter.writeObject(csr);
//        }
//        return sw.toString();
//    }
//
//
//    private void approveCsr(ApiClient apiClient,
//                            String csrName,
//                            String message) throws Exception {
//
//        String url = apiClient.getBasePath() +
//                "/apis/certificates.k8s.io/v1/certificatesigningrequests/" + csrName + "/approval";
//
//        String patchBody = "[{" +
//                "\"op\": \"add\"," +
//                "\"path\": \"/status/conditions\"," +x
//                "\"value\": [{" +
//                "\"type\": \"Approved\"," +
//                "\"status\": \"True\"," +
//                "\"reason\": \"JITAccess\"," +
//                "\"message\": \"" + message + "\"" +
//                "}]" +
//                "}]";
//
//        String token = ((ApiKeyAuth) apiClient.getAuthentication("BearerToken")).getApiKey();
//
//        OkHttpClient client = new OkHttpClient();
//
//        Request request = new Request.Builder()
//                .url(url)
//                .patch(RequestBody.create(
//                        MediaType.parse("application/json-patch+json"),
//                        patchBody
//                ))
//                .addHeader("Authorization", "Bearer " + token)
//                .addHeader("Accept", "application/json")
//                .build();
//
//        Response response = client.newCall(request).execute();
//
//        if (!response.isSuccessful()) {
//            throw new RuntimeException("Failed to approve CSR: " + response.code() + " -> " + response.body().string());
//        }
//
//        System.out.println("CSR Approved Successfully");
//    }
//
//
////    private void approveCsr(CertificatesV1Api certApi, String csrName, String message) throws Exception {
////        // Build approval condition
////        V1CertificateSigningRequestCondition approvedCondition = new V1CertificateSigningRequestCondition()
////                .type("Approved")
////                .status("True")
////                .reason("JITApproved")
////                .message(message)
////                .lastUpdateTime(OffsetDateTime.parse(Instant.now().toString()));
////
////        V1CertificateSigningRequestStatus status = new V1CertificateSigningRequestStatus()
////                .addConditionsItem(approvedCondition);
////
////        V1CertificateSigningRequest approval = new V1CertificateSigningRequest()
////                .metadata(new V1ObjectMeta().name(csrName))
////                .status(status);
////
////        // Replace approval subresource
////        certApi.replaceCertificateSigningRequestApproval(csrName, approval).execute();
////    }
//
//    private String waitForIssuedCert(CertificatesV1Api certApi, String csrName, int maxAttempts, long delayMs) throws Exception {
//        for (int i = 0; i < maxAttempts; i++) {
//            V1CertificateSigningRequest fetched = certApi.readCertificateSigningRequest(csrName).execute();
//            V1CertificateSigningRequestStatus status = fetched.getStatus();
//            if (status != null && status.getCertificate() != null && status.getCertificate().length > 0) {
//                byte[] certBytes = status.getCertificate();
//                String pem = new String(certBytes);
//                // The API returns PEM bytes; sometimes base64-encoded. Attempt to decode if needed:
//                if (!pem.contains("BEGIN CERTIFICATE")) {
//                    // sometimes it's base64; decode
//                    byte[] decoded = Base64.getDecoder().decode(certBytes);
//                    pem = new String(decoded);
//                }
//                return pem;
//            }
//            Thread.sleep(delayMs);
//        }
//        return null;
//    }
//
//    private void ensureRole(String namespace, String resourceType, String roleName, List<String> verbs, List<String> resourceNames) throws Exception {
//        // Create Role with given verbs and optional resourceNames (e.g., secret names)
//        RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api(initializeK8Client());
//
//        // Build Role
//        V1Role role = new V1Role()
//                .metadata(new V1ObjectMeta().name(roleName).namespace(namespace))
//                .rules(Collections.singletonList(
//                        new V1PolicyRule()
//                                .apiGroups(Collections.singletonList(""))
//                                .resources(Collections.singletonList(resourceType))
//                                .verbs(verbs)
//                                .resourceNames(resourceNames == null || resourceNames.isEmpty() ? null : resourceNames)
//                ));
//
//        try {
//            // try to create; if exists, replace (or you can patch)
//            rbacApi.createNamespacedRole(namespace, role).execute();
//        } catch (io.kubernetes.client.openapi.ApiException e) {
//            if (e.getCode() == 409) { // already exists
//                rbacApi.replaceNamespacedRole(roleName, namespace, role).execute();
//            } else {
//                throw e;
//            }
//        }
//    }
//
//    private void ensureRoleBinding(String namespace, String roleName, String group) throws Exception {
//        RbacAuthorizationV1Api rbacApi = new RbacAuthorizationV1Api(initializeK8Client());
//
//        String rbName = roleName + "-binding-" + (group != null ? group.replaceAll("[^a-z0-9-]", "-") : "group");
//
//        V1RoleBinding roleBinding = new V1RoleBinding()
//                .metadata(new V1ObjectMeta().name(rbName).namespace(namespace))
//                .subjects(Collections.singletonList(
//                        new RbacV1Subject()
//                                .kind("Group")
//                                .name(group)
//                                .apiGroup("rbac.authorization.k8s.io")
//                ))
//                .roleRef(new V1RoleRef()
//                        .apiGroup("rbac.authorization.k8s.io")
//                        .kind("Role")
//                        .name(roleName)
//                );
//
//        try {
//            rbacApi.createNamespacedRoleBinding(namespace, roleBinding).execute();
//        } catch (io.kubernetes.client.openapi.ApiException e) {
//            if (e.getCode() == 409) {
//                rbacApi.replaceNamespacedRoleBinding(rbName, namespace, roleBinding).execute();
//            } else {
//                throw e;
//            }
//        }
//    }
//}
