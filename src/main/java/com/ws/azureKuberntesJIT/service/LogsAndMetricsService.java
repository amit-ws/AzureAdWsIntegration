package com.ws.azureKuberntesJIT.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.monitor.query.LogsQueryClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.models.*;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.ws.azureKuberntesJIT.dto.K8sAuditLog;
import com.ws.azureKuberntesJIT.dto.K8sAuditLogAdvanced;
import com.ws.azureKuberntesJIT.dto.KubeAuditLog;
import com.ws.configuration.AzureAuthConfigurationFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1ServiceAccountList;
import io.kubernetes.client.util.Config;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class LogsAndMetricsService {
    CoreV1Api coreV1Api;
    final String clientId = "cb51e8d1-519c-4e18-9b2f-28d53e6badd1";
    final String clientSecret = "3F18Q~iM8DjCXg7rL~2.BZZPtdGNAzfOf2qXRdhC";
    final String tenantId = "f875ebf8-f5f0-4915-a2c9-4442e0118fd2";
    final String subscriptionId = "4769af8e-ca3d-448d-bd1a-80e03ed94158";
    final String workspaceId = "1ff52693-667a-48e8-a97a-9e8aa410c0d8";
//    final String workspaceId = "/subscriptions/4769af8e-ca3d-448d-bd1a-80e03ed94158/resourceGroups/azure-logs-rg/providers/Microsoft.OperationalInsights/workspaces/AzureLogAnalyticsWorkspace1";

    final K8LogHelperServiceOld k8LogHelperServiceOld;
    final K8LogHelperServiceNew k8LogHelperServiceNew;

    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;

    @Autowired
    public LogsAndMetricsService(K8LogHelperServiceOld k8LogHelperServiceOld, K8LogHelperServiceNew k8LogHelperServiceNew,
                                 AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.k8LogHelperServiceOld = k8LogHelperServiceOld;
        this.k8LogHelperServiceNew = k8LogHelperServiceNew;
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
    }


    public List<KubeAuditLog> testLogs() {
        return k8LogHelperServiceOld.testLogs();
    }


    public List<K8sAuditLog> fetchK8Logs() {
        initializeK8Client();
        List<String> saUserNames = fetchAllServiceAccounts();
        log.info("saUserNames: {}", saUserNames.size());
        String kqlQuery = buildKqlQuery(saUserNames);
//        log.info("KQL Query: {}", kqlQuery);
        log.info(" ");
        return runAzureLogAnalyticsQuery(kqlQuery);
    }


    public List<K8sAuditLogAdvanced> fetchK8LogsForCustomSAs() {
        initializeK8Client();

        List<String> customSaUserNames = fetchCustomServiceAccounts();
        log.info("customSaUserNames: {}", customSaUserNames.size());

        String kqlQuery = buildKqlQueryForCustomSaFilter(customSaUserNames);
        log.info("Query built up successfully");

        List<K8sAuditLogAdvanced> k8sAuditLogAdvanceds = k8LogHelperServiceOld.runAzureLogAnalyticsQuery(kqlQuery);
        log.info("k8sAuditLogAdvanceds total: {}", k8sAuditLogAdvanceds.size());
        return k8sAuditLogAdvanceds;
    }


    public List<K8sAuditLogAdvanced> fetchK8LogsForCustomSAs_NEW() {
        initializeK8Client();

        List<String> customSaUserNames = fetchCustomServiceAccounts();
        log.info("customSaUserNames: {}", customSaUserNames.size());

        String kqlQuery = buildKqlQueryForCustomSaFilter(customSaUserNames);
        log.info("Query built up successfully");

        LogsQueryResult result = fetchLogsQueryResult(kqlQuery);
        log.info("LogsQueryResult status: {}", result.getQueryResultStatus());

        List<K8sAuditLogAdvanced> k8sAuditLogAdvanceds = k8LogHelperServiceNew.convertToK8sAuditLogsAdvancedOptimized(result);
        log.info("k8sAuditLogAdvanceds total: {}", k8sAuditLogAdvanceds.size());
        return k8sAuditLogAdvanceds;
    }

    private LogsQueryResult fetchLogsQueryResult(String kqlQuery) {
        try {
            LogsQueryClient client = initializeAzureClient();
            return client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_7_DAYS);
        } catch (Exception ex) {
            throw new RuntimeException(ex.getMessage());
        }
    }


    private List<String> fetchCustomServiceAccounts() {
        try {
            V1ServiceAccountList saList = this.coreV1Api.listServiceAccountForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(saList)) {
                throw new RuntimeException("No ServiceAccounts found in cluster");
            }

            return saList.getItems().stream()
                    .filter(this::isCustomServiceAccount)
                    .map(this::toServiceAccountUsername)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch custom service accounts: " + e.getMessage(), e);
        }
    }

    private boolean isCustomServiceAccount(V1ServiceAccount sa) {
        V1ObjectMeta meta = sa.getMetadata();
        if (meta == null) return false;

        // 1. Check for Kubernetes-managed accounts (these are always system)
        if (isKubernetesManagedAccount(meta)) {
            return false;
        }

        // 2. Check for common system account patterns
        if (matchesCommonSystemPatterns(meta)) {
            return false;
        }

        // 3. Check for infrastructure/operator accounts (Helm, Operators, etc.)
        if (isInfrastructureAccount(meta)) {
            return false;
        }

        // If none of the above, assume it's custom
        return true;
    }

    private boolean isKubernetesManagedAccount(V1ObjectMeta meta) {
        // Kubernetes-created accounts have these characteristics:
        return "default".equals(meta.getName()) ||  // Default SA in every namespace
                meta.getAnnotations() != null &&
                        meta.getAnnotations().containsKey("kubernetes.io/service-account.name");
    }

    private boolean matchesCommonSystemPatterns(V1ObjectMeta meta) {
        String ns = meta.getNamespace();
        String name = meta.getName();

        // System namespaces typically end with -system, -operator, or are well-known
        boolean isSystemNamespace = ns == null || ns.endsWith("-system") ||
                ns.endsWith("-operator") || ns.startsWith("kube-") ||
                ns.equals("openshift") || ns.equals("cert-manager");

        // System accounts often have prefixes like kube-, system-, or component names
        boolean isSystemName = name.startsWith("system:") ||
                name.startsWith("kube-") || name.startsWith("cluster-") ||
                name.endsWith("-controller") || name.endsWith("-operator");

        return isSystemNamespace || isSystemName;
    }

    private boolean isInfrastructureAccount(V1ObjectMeta meta) {
        // Infrastructure tools often label their resources
        Map<String, String> labels = meta.getLabels();
        if (labels != null) {
            return labels.containsKey("app.kubernetes.io/component") ||
                    labels.containsKey("app.kubernetes.io/managed-by") ||
                    labels.containsKey("helm.sh/chart");
        }
        return false;
    }

    private String toServiceAccountUsername(V1ServiceAccount sa) {
        return String.format("system:serviceaccount:%s:%s",
                sa.getMetadata().getNamespace(),
                sa.getMetadata().getName());
    }

    private String buildKqlQueryForCustomSaFilter(List<String> customSaUsernames) {
        String saFilter = customSaUsernames.stream()
                .map(sa -> "'" + sa + "'")
                .collect(Collectors.joining(", "));

        return String.join("\n",
                "AzureDiagnostics",
                "| where Category == 'kube-audit'",
                "| extend log_json = parse_json(log_s)",
                "| where tostring(log_json.user.username) in (" + saFilter + ")",
                "| extend",
                "    PodName = tostring(log_json.user.extra['authentication.kubernetes.io/pod-name']),",
                "    RequestObjectStr = tostring(iff(isnull(log_json.requestObject), parse_json('{}'), log_json.requestObject)),",
                "    ResponseObjectStr = tostring(iff(isnull(log_json.responseObject), parse_json('{}'), log_json.responseObject))",
                "| project",
                "    TimeGenerated,",
                "    Namespace = tostring(log_json.objectRef.namespace),",
                "    PodName,",
                "    Verb = tostring(log_json.verb),",
                "    User = tostring(log_json.user.username),",
                "    UserUID = tostring(log_json.user.uid),",
                "    UserGroups = tostring(log_json.user.groups),",
                "    Resource = tostring(log_json.objectRef.resource),",
                "    SubResource = tostring(log_json.objectRef.subresource),",
                "    ResourceName = tostring(log_json.objectRef.name),",
                "    RequestURI = tostring(log_json.requestURI),",
                "    SourceIPs = tostring(log_json.sourceIPs),",
                "    UserAgent = tostring(log_json.userAgent),",
                "    ResponseStatusCode = toint(log_json.responseStatus.code),",
                "    ResponseStatusReason = tostring(log_json.responseStatus.reason),",
                "    Stage = tostring(log_json.stage),",
                "    Annotations = tostring(log_json.annotations),",
                "    RequestReceivedTimestamp = todatetime(log_json.requestReceivedTimestamp),",
                "    AuditID = tostring(log_json.auditID),",
                "    RequestObjectStr,",
                "    ResponseObjectStr",
                "| sort by TimeGenerated desc",
                "| limit 2000"
        );
    }

    private void runAzureLogAnalyticsQueryForCustomSA(String kqlQuery) {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();

        LogsQueryClient client = new LogsQueryClientBuilder()
                .credential(credential)
                .buildClient();

        try {
            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);

            if (result.getTable() == null || result.getTable().getRows().isEmpty()) {
                System.out.println("No matching NHI activity logs found.");
                return;
            }

            // Log each row (as column name:value)
            result.getTable().getRows().forEach(row -> {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < result.getTable().getColumns().size(); i++) {
                    String colName = result.getTable().getColumns().get(i).getColumnName();
                    Object value = row.getColumnValue(colName);
                    values.add(colName + ": " + value);
                }
                System.out.println(String.join(" | ", values));
            });

        } catch (Exception e) {
            throw new RuntimeException("Error running KQL query: " + e.getMessage(), e);
        }
    }


    private void runAzureLogAnalyticsQuery2(String kqlQuery) {

        try {
            LogsQueryClient client = initializeAzureClient();
            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);
            log.debug("Received query result with status: {}", result.getQueryResultStatus()); // Log query status

            if (result.getTable() == null) {
                log.error("Query returned null table");
                throw new RuntimeException("No result table returned from query.");
            }

            // Log table structure for debugging
            log.info("Query result table details:");
            log.info("- Column count: {}", result.getTable().getColumns().size());
            log.info("- Row count: {}", result.getTable().getRows().size());

            // Log column names
            List<String> columnNames = result.getTable().getColumns().stream()
                    .map(LogsTableColumn::getColumnName)
                    .collect(Collectors.toList());
            log.info("Columns: {}", String.join(", ", columnNames));

            if (result.getTable().getRows().isEmpty()) {
                log.warn("Query returned empty result set");
                throw new RuntimeException("No matching NHI activity logs found.");
            }

            // Log first few rows for sample data (limit to 3 rows for brevity)
            int sampleRows = Math.min(3, result.getTable().getRows().size());
            log.info("Sample rows (first {} of {}):", sampleRows, result.getTable().getRows().size());
            for (int i = 0; i < sampleRows; i++) {
                log.info("Row {}: {}", i + 1, result.getTable().getRows().get(i));
            }

        } catch (Exception e) {
            log.error("Error running KQL query: {}", e.getMessage(), e);
            throw new RuntimeException("Error running KQL query: " + e.getMessage(), e);
        }
    }


    private List<String> fetchAllServiceAccounts() {
        try {
            V1ServiceAccountList v1ServiceAccountList = this.coreV1Api.listServiceAccountForAllNamespaces().execute();
            if (ObjectUtils.isEmpty(v1ServiceAccountList)) {
                throw new RuntimeException("NO SERVICE_ACCOUNT(s) found");
            }
            List<String> nhiUsernames = new ArrayList<>();

            for (V1ServiceAccount serviceAccount : v1ServiceAccountList.getItems()) {
                String namespace = serviceAccount.getMetadata().getNamespace();
                String name = serviceAccount.getMetadata().getName();
                String username = String.format("system:serviceaccount:%s:%s", namespace, name);
                nhiUsernames.add(username);
            }

            return nhiUsernames;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }

    }

    private String buildKqlQuery(List<String> saUsernames) {
        String saFilter = String.join(", ", saUsernames.stream().map(sa -> "'" + sa + "'").toList());

        return "AzureDiagnostics " +
                "| where Category == 'kube-audit' " +
                "| extend audit = parse_json(log_s) " +
                "| where audit.user.username in (" + saFilter + ") " +
                "| project TimeGenerated, NHI = audit.user.username, Verb = audit.verb, " +
                "Resource = tostring(audit.objectRef.resource), ResourceName = tostring(audit.objectRef.name), " +
                "Namespace = tostring(audit.objectRef.namespace), StatusCode = tostring(audit.responseStatus.code), " +
                "SourceIP = tostring(audit.sourceIPs[0]) " +
                "| sort by TimeGenerated desc";
    }


    private List<K8sAuditLog> runAzureLogAnalyticsQuery(String kqlQuery) {
        List<K8sAuditLog> auditLogs = new ArrayList<>();

        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();

        LogsQueryClient client = new LogsQueryClientBuilder()
                .credential(credential)
                .buildClient();
        try {
            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);

            if (result.getTable() == null || result.getTable().getRows().isEmpty()) {
                throw new RuntimeException("No matching NHI activity logs found.");
            }

//            log.info("NHI Activity Logs:");
//            result.getTable().getRows().forEach(row -> {
//                log.info("Time: {} | NHI: {} | Verb: {} | Resource: {}/{} | Namespace: {} | Status: {} | SourceIP: {}",
//                        row.getColumnValue("TimeGenerated"),
//                        row.getColumnValue("NHI"),
//                        row.getColumnValue("Verb"),
//                        row.getColumnValue("Resource"),
//                        row.getColumnValue("ResourceName"),
//                        row.getColumnValue("Namespace"),
//                        row.getColumnValue("StatusCode"),
//                        row.getColumnValue("SourceIP"));
//            });

            LogsTable table = result.getTable();

            Map<String, Integer> columnIndexMap = new HashMap<>();
            List<LogsTableColumn> columns = table.getColumns();

            log.info("columns size: {}", columns.size());

            for (LogsTableColumn column : table.getColumns()) {
                log.info("Column: {}", column.getColumnName());
            }


//            for (int i = 0; i < columns.size(); i++) {
//                columnIndexMap.put(columns.get(i).getColumnName(), i);
//            }
//
//            for (LogsTableRow row : table.getRows()) {
//                List<LogsTableCell> cells = row.getRow();
//
//                K8sAuditLog logEntry = new K8sAuditLog();
////                logEntry.time = getCellValue(cells, columnIndexMap.get("TimeGenerated"));
////                logEntry.username = getCellValue(cells, columnIndexMap.get("user"));
////                logEntry.verb = getCellValue(cells, columnIndexMap.get("verb"));
////                logEntry.resource = getCellValue(cells, columnIndexMap.get("resource"));
////                logEntry.subresource = getCellValue(cells, columnIndexMap.get("subresource"));
////                logEntry.namespace = getCellValue(cells, columnIndexMap.get("namespace"));
////                logEntry.status = getCellValue(cells, columnIndexMap.get("status"));
////                logEntry.sourceIp = getCellValue(cells, columnIndexMap.get("sourceIP"));
//
//                log.info("Log Entry - TimeGenerated: {}, User: {}, Verb: {}, Resource: {}, Subresource: {}, Namespace: {}, Status: {}, SourceIP: {}",
//                        getCellValue(cells, columnIndexMap.get("TimeGenerated")),
//                        getCellValue(cells, columnIndexMap.get("user")),
//                        getCellValue(cells, columnIndexMap.get("verb")),
//                        getCellValue(cells, columnIndexMap.get("resource")),
//                        getCellValue(cells, columnIndexMap.get("subresource")),
//                        getCellValue(cells, columnIndexMap.get("namespace")),
//                        getCellValue(cells, columnIndexMap.get("status")),
//                        getCellValue(cells, columnIndexMap.get("sourceIP")));
//
//                auditLogs.add(logEntry);
//            }

        } catch (Exception e) {
            throw new RuntimeException("Error running KQL query: " + e.getMessage());
        }

        return auditLogs;
    }

    private String getCellValue(List<LogsTableCell> cells, Integer index) {
        if (index == null || index >= cells.size()) return null;
        return cells.get(index).getValueAsString();
    }


    private void initializeK8Client() {
        String rgName = "ws-test-aks-rg";
        String clusterName = "ws-test-aks-cluster-1";
        AzureResourceManager azureResourceManager = getAzureResourceManager(clientId, clientSecret, tenantId, subscriptionId);
        KubernetesCluster cluster = azureResourceManager
                .kubernetesClusters()
                .getByResourceGroup(rgName, clusterName);
        String kubeConfigContent = new String(cluster.adminKubeConfigs().get(0).value());
        String[] extractedValues = extractServerAndTokenFromKubeConfigYAML(kubeConfigContent);

        ApiClient client = Config.fromToken(extractedValues[0], extractedValues[1]);
        client.setVerifyingSsl(false);
        Configuration.setDefaultApiClient(client);

        this.coreV1Api = new CoreV1Api();
    }


    private static String[] extractServerAndTokenFromKubeConfigYAML(String config) {
        String[] result = new String[2];

        String serverPrefix = "server: ";
        int serverStart = config.indexOf(serverPrefix) + serverPrefix.length();
        int serverEnd = config.indexOf("\n", serverStart);
        result[0] = config.substring(serverStart, serverEnd).trim();

        String tokenPrefix = "token: ";
        int tokenStart = config.indexOf(tokenPrefix) + tokenPrefix.length();
        int tokenEnd = config.indexOf("\n", tokenStart);
        result[1] = config.substring(tokenStart, tokenEnd).trim();

        return result;
    }


    private LogsQueryClient initializeAzureClient() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .tenantId(tenantId)
                .clientSecret(clientSecret)
                .build();

        return new LogsQueryClientBuilder()
                .credential(credential)
                .buildClient();
    }

}
