//package com.ws.certificateJIT.azure;
//
//import com.azure.resourcemanager.AzureResourceManager;
//import com.azure.resourcemanager.authorization.models.PasswordCredential;
//import com.azure.resourcemanager.authorization.models.ServicePrincipal;
//import com.ws.configuration.AzureAuthConfigurationFactory;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.time.Duration;
//import java.time.Instant;
//import java.time.ZoneOffset;
//import java.time.format.DateTimeFormatter;
//import java.util.Map;
//
//
//@Slf4j
//@Service
//public class AzureCertificateJITService {
//
//    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;
//
//    @Autowired
//    public AzureCertificateJITService(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
//        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
//    }
//
//
//    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
//        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
//    }
//
//
//    public String generateAzureJitAccessScript(
//            String requestingUserId,
//            String resourceScope,
//            String roleDefinitionId,
//            long expirationSeconds) {
//
//        log.info("userId: {}", requestingUserId);
//        log.info("resource: {}", resourceScope);
//        log.info("definitinId: {}", roleDefinitionId);
//
//        try {
//            // Step 1: Get admin resource manager to create SP and assign roles
//            AzureResourceManager armAdmin = getAzureResourceManager("cb51e8d1-519c-4e18-9b2f-28d53e6badd1", "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ",
//                    "f875ebf8-f5f0-4915-a2c9-4442e0118fd2", "4769af8e-ca3d-448d-bd1a-80e03ed94158");
//
//            // Step 2: Create a temporary Service Principal with password credential
//            String spName = "jit-sp-" + requestingUserId + "-" + System.currentTimeMillis();
//
//            log.info("Creating temporary Service Principal: {}", spName);
//
//
//            log.info("total: {}", armAdmin.accessManagement().servicePrincipals().list().stream().count());
//
//            ServicePrincipal servicePrincipal = armAdmin.accessManagement().servicePrincipals()
//                    .define(spName)
//                    .withNewApplication(spName)
//                    .definePasswordCredential("jit-password")
//                    .withDuration(Duration.ofSeconds(expirationSeconds))
//                    .attach()
//                    .create();
//
//            String spObjectId = servicePrincipal.id();
//            String spClientId = servicePrincipal.applicationId();
//
//            log.info("Service Principal created. ObjectId: {}, ClientId: {}", spObjectId, spClientId);
//
//            // Step 3: Get the auto-generated password from the credential map
//            Map<String, PasswordCredential> passwordCredentials = servicePrincipal.passwordCredentials();
//            String spPassword = passwordCredentials.get("jit-password").value();
//
//            log.info("Password credential retrieved for Service Principal");
//
//            // Step 4: Assign RBAC role to the Service Principal for the resource scope
//            String roleAssignmentName = "jit-role-" + System.currentTimeMillis();
//            log.info("Assigning role {} to Service Principal for scope: {}", roleDefinitionId, resourceScope);
//
//            armAdmin.accessManagement().roleAssignments()
//                    .define(roleAssignmentName)
//                    .forObjectId(spObjectId)
//                    .withRoleDefinition(roleDefinitionId)
//                    .withScope(resourceScope)
//                    .create();
//
//            log.info("RBAC role assigned successfully");
//
//            // Step 5: Calculate expiration time
//            long expirationTimestamp = System.currentTimeMillis() + (expirationSeconds * 1000);
//            String expirationTime = Instant.ofEpochMilli(expirationTimestamp)
//                    .atZone(ZoneOffset.UTC)
//                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
//
//            // Step 6: Generate the bash script with embedded credentials
//            String bashScript = generateBashScript(
//                    spClientId,
//                    spPassword,
//                    "f875ebf8-f5f0-4915-a2c9-4442e0118fd2",
//                    "4769af8e-ca3d-448d-bd1a-80e03ed94158",
//                    resourceScope,
//                    expirationTime,
//                    requestingUserId
//            );
//
//            log.info("Bash script generated successfully for user: {}", requestingUserId);
//
//            log.info(" ");
//            return bashScript;
//        } catch (Exception e) {
//            log.error("Error generating Azure JIT access script", e);
//            throw new RuntimeException("Failed to generate Azure JIT access script: " + e.getMessage(), e);
//        }
//    }
//
//
//    /**
//     * Generate the bash script with embedded Service Principal credentials
//     */
//    private String generateBashScript(
//            String appId,
//            String password,
//            String tenantId,
//            String subscriptionId,
//            String resourceScope,
//            String expirationTime,
//            String requestingUserId) {
//
//        return "#!/bin/bash\n" +
//                "\n" +
//                "# Azure JIT Access Script\n" +
//                "# Generated for: " + requestingUserId + "\n" +
//                "# Resource Scope: " + resourceScope + "\n" +
//                "# Expires: " + expirationTime + "\n" +
//                "\n" +
//                "export AZURE_CONFIG_DIR=\"/tmp/jit-session-$$\"\n" +
//                "export AZURE_SUBSCRIPTION_ID=\"" + subscriptionId + "\"\n" +
//                "\n" +
//                "echo \"🔐 Authenticating as Service Principal...\"\n" +
//                "\n" +
//                "# Login as Service Principal (non-interactive)\n" +
//                "az login --service-principal \\\n" +
//                "  -u \"" + appId + "\" \\\n" +
//                "  -p \"" + password + "\" \\\n" +
//                "  --tenant \"" + tenantId + "\" \\\n" +
//                "  --output none\n" +
//                "\n" +
//                "if [ $? -ne 0 ]; then\n" +
//                "  echo \"❌ Authentication failed\"\n" +
//                "  exit 1\n" +
//                "fi\n" +
//                "\n" +
//                "# Set subscription\n" +
//                "az account set --subscription \"$AZURE_SUBSCRIPTION_ID\"\n" +
//                "\n" +
//                "echo \"✅ Authenticated successfully\"\n" +
//                "echo \"📋 Scope: " + resourceScope + "\"\n" +
//                "echo \"⏰ Expires: " + expirationTime + "\"\n" +
//                "echo \"\"\n" +
//                "echo \"You can now run Azure CLI commands, e.g:\"\n" +
//                "echo \"  az resource list --resource-group <rg>\"\n" +
//                "echo \"  az storage account keys list --resource-group <rg> --account-name <storage>\"\n" +
//                "echo \"\"\n" +
//                "echo \"Type 'exit' to end the session and cleanup.\"\n" +
//                "\n" +
//                "# Start an interactive shell\n" +
//                "$SHELL\n" +
//                "\n" +
//                "# Cleanup on exit\n" +
//                "echo \"🧹 Cleaning up temporary credentials...\"\n" +
//                "unset AZURE_CONFIG_DIR\n" +
//                "echo \"✅ Session ended.\"\n";
//    }
//
//
//    public void cleanupExpiredJitResources(String servicePrincipalId, String azureRoleAssignmentId) {
//        try {
//            AzureResourceManager armAdmin = getAzureResourceManager("cb51e8d1-519c-4e18-9b2f-28d53e6badd1", "yye8Q~FxfhNLvs07nM3PIPF0.H0zAvcvQ1Z5FcCJ",
//                    "f875ebf8-f5f0-4915-a2c9-4442e0118fd2", "4769af8e-ca3d-448d-bd1a-80e03ed94158");
//
//            log.info("Cleaning up Service Principal: {}", servicePrincipalId);
//            armAdmin.accessManagement().servicePrincipals().deleteById(servicePrincipalId);
//            log.info("Service Principal deleted successfully");
//            log.info("Cleaning up Azure Role assignment for the principle ID");
//            armAdmin.accessManagement().roleAssignments().deleteById(azureRoleAssignmentId);
//            log.info("Azure Role assignment deleted successfully");
//
//        } catch (Exception e) {
//            log.error("Error cleaning up Service Principal: {}", servicePrincipalId, e);
//        }
//    }
//}
