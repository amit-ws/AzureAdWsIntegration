//package com.ws.azureKuberntesJIT.service;
//
//
//import io.kubernetes.client.openapi.ApiClient;
//import org.yaml.snakeyaml.DumperOptions;
//import org.yaml.snakeyaml.Yaml;
//
//import java.io.StringWriter;
//import java.util.*;
//
//public class KubeconfigBuilder {
//
//    /**
//     * Builds a kubeconfig YAML string containing:
//     * - cluster (server, certificate-authority-data if present in ApiClient)
//     * - user (client-certificate-data, client-key-data)
//     * - context linking them
//     */
//    public static String buildKubeconfigFromApiClient(ApiClient client,
//                                                      String username,
//                                                      java.security.KeyPair keyPair,
//                                                      String issuedCertPem) throws Exception {
//
//        // server
//        String server = client.getBasePath();
//
//        // try to get CA data from client - ApiClient stores it in getSslCaCert() maybe not accessible
//        // as a fallback, if client.getSslCaCert() is available and contains bytes, encode it.
//        String caData = null;
//        if (client.getSslCaCert() != null) {
//            try (java.io.InputStream is = client.getSslCaCert()) {
//                byte[] caBytes = is.readAllBytes();
//                if (caBytes.length > 0) {
//                    caData = Base64.getEncoder().encodeToString(caBytes);
//                }
//            } catch (Exception ignore) {}
//        }
//
//        // client cert (issuedCertPem already a PEM string). We need base64 of PEM bytes
//        String clientCertData = Base64.getEncoder().encodeToString(issuedCertPem.getBytes());
//
//        // client key PEM: convert private key to PKCS#8 PEM
//        String clientKeyPem = pemFromPrivateKey(keyPair.getPrivate());
//        String clientKeyData = Base64.getEncoder().encodeToString(clientKeyPem.getBytes());
//
//        String clusterName = "cluster-" + UUID.randomUUID().toString().substring(0, 6);
//        String userName = username;
//        String contextName = userName + "@" + clusterName;
//
//        Map<String, Object> kube = new LinkedHashMap<>();
//        kube.put("apiVersion", "v1");
//        kube.put("kind", "Config");
//
//        List<Map<String, Object>> clusters = new ArrayList<>();
//        Map<String, Object> clusterMap = new LinkedHashMap<>();
//        clusterMap.put("name", clusterName);
//        Map<String, Object> clusterDetail = new LinkedHashMap<>();
//        clusterDetail.put("server", server);
//        if (caData != null) clusterDetail.put("certificate-authority-data", caData);
//        clusterMap.put("cluster", clusterDetail);
//        clusters.add(clusterMap);
//
//        List<Map<String, Object>> users = new ArrayList<>();
//        Map<String, Object> userMap = new LinkedHashMap<>();
//        userMap.put("name", userName);
//        Map<String, Object> userDetail = new LinkedHashMap<>();
//        userDetail.put("client-certificate-data", clientCertData);
//        userDetail.put("client-key-data", clientKeyData);
//        userMap.put("user", userDetail);
//        users.add(userMap);
//
//        List<Map<String, Object>> contexts = new ArrayList<>();
//        Map<String, Object> ctxMap = new LinkedHashMap<>();
//        ctxMap.put("name", contextName);
//        Map<String, Object> ctxDetail = new LinkedHashMap<>();
//        ctxDetail.put("cluster", clusterName);
//        ctxDetail.put("user", userName);
//        ctxMap.put("context", ctxDetail);
//        contexts.add(ctxMap);
//
//        kube.put("clusters", clusters);
//        kube.put("users", users);
//        kube.put("contexts", contexts);
//        kube.put("current-context", contextName);
//
//        DumperOptions dumperOptions = new DumperOptions();
//        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
//        Yaml yaml = new Yaml(dumperOptions);
//        StringWriter writer = new StringWriter();
//        yaml.dump(kube, writer);
//        return writer.toString();
//    }
//
//    private static String pemFromPrivateKey(java.security.PrivateKey privateKey) throws Exception {
//        // Build PKCS#8 PEM
//        byte[] keyBytes = privateKey.getEncoded();
//        String base64 = Base64.getEncoder().encodeToString(keyBytes);
//        StringBuilder pem = new StringBuilder();
//        pem.append("-----BEGIN PRIVATE KEY-----\n");
//        int idx = 0;
//        while (idx < base64.length()) {
//            int end = Math.min(idx + 64, base64.length());
//            pem.append(base64, idx, end).append("\n");
//            idx = end;
//        }
//        pem.append("-----END PRIVATE KEY-----\n");
//        return pem.toString();
//    }
//}
