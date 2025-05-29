package com.ws.azureKuberntesJIT.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.monitor.query.LogsQueryClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.models.*;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.containerservice.models.KubernetesCluster;
import com.ws.azureKuberntesJIT.dto.K8sAuditLog;
import com.ws.configuration.AzureAuthConfigurationFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
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


    final AzureAuthConfigurationFactory azureAuthConfigurationFactory;

    @Autowired
    public LogsAndMetricsService(AzureAuthConfigurationFactory azureAuthConfigurationFactory) {
        this.azureAuthConfigurationFactory = azureAuthConfigurationFactory;
    }

    private AzureResourceManager getAzureResourceManager(String clientId, String clientSecret, String tenantId, String subscriptionId) {
        return azureAuthConfigurationFactory.createAzureResourceClient(clientId, clientSecret, tenantId, subscriptionId);
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

}
