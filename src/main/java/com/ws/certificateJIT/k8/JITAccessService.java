package com.ws.certificateJIT.k8;


import io.kubernetes.client.openapi.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class JITAccessService {
    final String caCertBase64 = "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUU2VENDQXRHZ0F3SUJBZ0lSQU9JbVRxVDFyZVdKQVlINUNlUVc0WkF3RFFZSktvWklodmNOQVFFTEJRQXcKRFRFTE1Ba0dBMVVFQXhNQ1kyRXdJQmNOTWpVd01qSXdNVEl4T0RRMFdoZ1BNakExTlRBeU1qQXhNakk0TkRSYQpNQTB4Q3pBSkJnTlZCQU1UQW1OaE1JSUNJakFOQmdrcWhraUc5dzBCQVFFRkFBT0NBZzhBTUlJQ0NnS0NBZ0VBCjNIMklTRVVZT1lJcmdoOHBlV3MrZHF2U2J2K1A4dThLWTFDZ2dkTWx2dzViTXMvaU90MXByK2U4Rm9vMUkzMDUKYnBnKzFqQ2NvTUJsNTRxdVc1MGFsNVFhSE1QblZCM0xrZ2g4M01teU5ITWVBWHpudFBTMEI2RllKc25QaldZVgo4U2tNYjZIbFB5RnpKNk5oTHZXQ2t3d3BVOTFFZ3ZNZUJvTVZ0dGhUYmZHamM1eFNJdEJLaXpBeFhqWENlcVZzCjY3ZGlpdC9YNDhESDNnM05MNk5kQmVpdkluYktMUVJWdUg3MzlHbUFsQ2drSU0yYjFzNnZaK3ZDaHpoZFVLMVIKV2IxVER3UHJidXhxZUE0YVB0dVV1OC9NNCtVRDhEeC8vNStKbHRnengyYUFpQXN5OU9YVGlNUk9HUnpOcCs1dQovbzZZdzJveFVwRGVJOUVUZ214ZVp4OTdNR3g5WU15WDY4VFFiTUhwOUpEV0J2dkcwMHlLeDFqMWJQVWRBaGF3CjlBbjFlT1NZcmdOdHdnRStBb1JDcXJhV2dQUDZyZ3Q5bWhqZE44UGpxNElRQnpHaUhObGxrUk5yTStwSEllTFQKNDZWVC81T2F3d2pHUXoyWFFGcFdVa0RNQSs1L0RsS1QwcXFtN0l0SHQzTVpWWXBwWWdNQWtkc25PTmRHbkhHdgowQkZaUnpWK3lHNHFjUWZDMTFzc2hmSzNPRUhsZERCR2grbzJqRWVXTjZTdHQrL3J3TU1NWlVxY3NRUW9hNSswCmtFT0cyenpSWUgycnM1aGVHMzIwbVVQT3lzRXNsdGFSUUs5bEVjakROdHF2eVFXUjlubjU0VjhLOThLQWJCZTYKNkhkYWM2M0VhQy8vME5zMm10R1Nhd1lnSFJXYkNLa2VQQlVpdjZxeStuVUNBd0VBQWFOQ01FQXdEZ1lEVlIwUApBUUgvQkFRREFnS2tNQThHQTFVZEV3RUIvd1FGTUFNQkFmOHdIUVlEVlIwT0JCWUVGUHFtVDR6aHVjQVAzSnBzCkNZTXcrUXcxU09wZE1BMEdDU3FHU0liM0RRRUJDd1VBQTRJQ0FRQ3dNR3JQWlVYa1kzUmcwWDZuWThZSW5JWGsKTGZKU1FERkJYUDBOTk11RVRJcUczeWwyRGJldXNmWGkzaHZaZ0pmS01aYXNueDVoNmIxUFFOMU9RVHpSQUdTQgpIOUk0bnMyNVluUUMvd0puT2JuVHhSUHI0YzBlUURnMzRCam4weUhSbkQvT0o5cHJrb2F5eUxUcC9ySXZ2QU1pCng4c1lNVjM5cEVXOEJlWGhScGJnUlRQdDhlZ2FncWVRZ2E2bVFRYmgyT3prV0VzRjIvS09WbUpUVThmWDVWQkwKWXNJKzJGeFp3UXJ2YmtLQmlQdDJyWTl3U2lKWW16dEVxOVB3WWYxVkVWN1hGMzV5azBmbFJxVG5KaWtDZVhldwp0K3BMR2hDa25oa0FiR2pNUlo0d1RPb2dyQnA0T1hTNzB3TUpwcWVMQXJIZXBtMWFuRGRncDhjUmhmVDFsalJrClBvU0x1TStHK0IxMXBTQU1TSnBDV1B0c1FGU3ZhWmo3MXFQSXZKdU9zNHV4QXhMUjJPTlM1UFBwUEdGT1I2THoKRDdQbmhEbS9oTVFsYVQ2L1ZpOFBJbHVhSkp3dzdQOGlHaWtuRE80MjA3N0FjcElTNzJiL2hzbnFRRlMzOERGSAp6anFGTVB0N3MreEEzOHpNUFQ0Wmt6UjZwdXU2RG9laXJER2JCTnpMUUdGdHBBSDVpdUNJUmJjSmNHUnhmV1EwCkRSbXpHcEpJT3U2YS9XOFBNSkNFNWFoeUFSelpEWTFsK0dNaHNFU0lMbjJnZWdVTTVGRVVCWmxUeWJONUdsdUkKRlU4cTUwZDFRakF3Ky9SRjFpanZuV2hPUElmU1gzMm5VNnRGcHRJeVo1UktyZDRwNWNHMmNxQ3QyUExqNWtwVwp1WW1hYmJ6RmJlY3JZcFlvSnc9PQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tCg==";
    final String server = "https://ws-test-aks-cluster-1-dns-8t33e8yw.hcp.eastus.azmk8s.io:443";
    private final ApiClient apiClient;
    private final CertificateSigningService certificateSigningService;
    private final RoleBindingService roleBindingService;

    public JITAccessService(
            ApiClient apiClient, CertificateSigningService certificateSigningService,
            RoleBindingService roleBindingService) {
        this.apiClient = apiClient;
        this.certificateSigningService = certificateSigningService;
        this.roleBindingService = roleBindingService;
    }

    /**
     * Grant JIT access: Create CSR, approve, get certificate, create RoleBinding
     */
    public AccessResponse grantJITAccess(AccessRequest request) throws Exception {
        log.info("Processing JIT access request for user: {} in namespace: {}",
                request.getUserName(), request.getNamespace());

        try {
            // Step 1: Create CSR and generate certificate
            CertificateSigningService.CertificateSigningRequestData csrData =
                    certificateSigningService.createCertificateSigningRequest(
                            request.getUserId(),
                            request.getUserName(),
                            request.getGroups(),
                            request.getDurationSeconds()
                    );

            String csrName = csrData.getCsrName();
            log.info("CSR created: {}", csrName);

            // Step 2: Approve the CSR
            certificateSigningService.approveCertificateSigningRequest(csrName);
            log.info("CSR approved: {}", csrName);

            // Step 3: Retrieve signed certificate
            String signedCertificateBase64 = certificateSigningService.getSignedCertificate(csrName, 10);

            String certificatePem = EncodingUtil.decodeBase64(signedCertificateBase64);
            log.info("Certificate retrieved: {}", csrName);

            String roleName = roleBindingService.createRole(request.getNamespace(), request.getResource(), request.getVerbs());

            // Step 4: Create RoleBinding
            String roleBindingName = roleBindingService.generateRoleBindingName(roleName);

            roleBindingService.createRoleBinding(
                    request.getNamespace(),
                    roleName,
                    request.getUserName(),
                    roleBindingName
            );

            log.info("RoleBinding created: {}", roleBindingName);

            // Step 5: Generate kubeconfig
            String kubeconfig = generateKubeconfig(
                    request.getUserName(),
                    certificatePem,
                    csrData.getPrivateKeyPem(),
                    "ws-test-aks-cluster-1",
                    server,
                    caCertBase64
            );

            log.info("\n");
            System.out.printf(kubeconfig);
            log.info("\n");


            AccessResponse response = AccessResponse.builder()
                    .certificateSigningRequestName(csrName)
                    .status("SUCCESS")
                    .message("JIT access granted successfully")
                    .certificatePem(csrData.getPrivateKeyBase64())
                    .privateKeyPem(csrData.getPrivateKeyBase64())
                    .kubeconfig(kubeconfig)
                    .expirationTime(csrData.getExpirationTimestamp())
                    .expirationTimeStamp(convertExpirationTImeToTImestamp(csrData.getExpirationTimestamp()))
                    .build();

            log.info("JIT access granted: {}", request.getUserName());
            return response;

        } catch (Exception e) {
            log.error("Error granting JIT access: ", e);
            throw new Exception("Failed to grant JIT access: " + e.getMessage(), e);
        }
    }

    private String convertExpirationTImeToTImestamp(Long expirationTime) {
        return Instant.ofEpochMilli(expirationTime)
                .atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }


    public void revokeJITAccess(String csrName, String namespace, String roleName, String roleBindingName)
            throws Exception {

        log.info("Revoking JIT access - CSR: {}, RoleName: {}, RoleBinding: {}", csrName, roleName, roleBindingName);

        try {
            certificateSigningService.deleteCertificateSigningRequest(csrName);
            roleBindingService.deleRole(namespace, roleName);
            roleBindingService.deleteRoleBinding(namespace, roleBindingName);
            log.info("JIT access revoked successfully");
        } catch (Exception e) {
            log.error("Error revoking JIT access: ", e);
            throw e;
        }
    }


    private String generateKubeconfig(
            String userName,
            String certificatePem,
            String privateKeyPem,
            String clusterName,
            String server,
            String caCertBase64) {

        String certBase64 = EncodingUtil.encodeBase64(certificatePem);
        String keyBase64 = EncodingUtil.encodeBase64(privateKeyPem);
        String contextName = userName + "@" + clusterName;

        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: %s
                  cluster:
                    server: %s
                    certificate-authority-data: %s
                contexts:
                - name: %s
                  context:
                    cluster: %s
                    user: %s
                current-context: %s
                users:
                - name: %s
                  user:
                    client-certificate-data: %s
                    client-key-data: %s
                """.formatted(
                clusterName,
                server,
                caCertBase64,
                contextName,
                clusterName,
                userName,
                contextName,
                userName,
                certBase64,
                keyBase64
        );
    }


//    private String generateKubeconfig(
//            String userName,
//            String certificatePem,
//            String privateKeyPem,
//            String clusterName,
//            String server,
//            String caCertBase64) {
//
//        // These must be base64-encoded PEM blobs for kubeconfig
//        String certBase64 = EncodingUtil.encodeBase64(certificatePem);
//        String keyBase64 = EncodingUtil.encodeBase64(privateKeyPem);
//
//        String contextName = userName + "@" + clusterName;
//
//        return String.format(
//                "apiVersion: v1\n" +
//                        "kind: Config\n" +
//                        "clusters:\n" +
//                        "- name: %s\n" +
//                        "  cluster:\n" +
//                        "    server: %s\n" +
//                        "    certificate-authority-data: %s\n" +
//                        "contexts:\n" +
//                        "- name: %s\n" +
//                        "  context:\n" +
//                        "    cluster: %s\n" +
//                        "    user: %s\n" +
//                        "current-context: %s\n" +
//                        "users:\n" +
//                        "- name: %s\n" +
//                        "  user:\n" +
//                        "    client-certificate-data: %s\n" +
//                        "    client-key-data: %s\n",
//                // format args:
//                clusterName,
//                server,
//                caCertBase64,
//                contextName,
//                clusterName,
//                userName,
//                contextName,
//                userName,
//                certBase64,
//                keyBase64
//        );
//    }

}
