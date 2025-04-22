//package com.ws.logMcpServer.service;
//
//import com.azure.core.credential.TokenCredential;
//import com.azure.identity.ClientSecretCredential;
//import com.azure.identity.ClientSecretCredentialBuilder;
//import com.azure.monitor.query.LogsQueryClient;
//import com.azure.monitor.query.LogsQueryClientBuilder;
//import com.azure.monitor.query.MetricsQueryClient;
//import com.azure.monitor.query.MetricsQueryClientBuilder;
//import com.azure.monitor.query.models.*;
//import com.azure.resourcemanager.AzureResourceManager;
//import com.azure.resourcemanager.compute.models.VirtualMachine;
//import com.azure.resourcemanager.resources.ResourceManager;
//import com.azure.core.management.profile.AzureProfile;
//import com.azure.core.management.AzureEnvironment;
//
//import java.time.Duration;
//import java.util.Arrays;
//import java.util.List;
//
//public class AzureVmLogsMetricsService {
//
//    private final LogsQueryClient logsQueryClient;
//    private final MetricsQueryClient metricsQueryClient;
//    private final AzureResourceManager azureResourceManager;
//
//    private final String workspaceId; // Log Analytics Workspace ID
//
//    public AzureVmLogsMetricsService(String clientId, String clientSecret, String tenantId, String subscriptionId, String workspaceId) {
//
//        TokenCredential credential = new ClientSecretCredentialBuilder()
//                .clientId(clientId)
//                .clientSecret(clientSecret)
//                .tenantId(tenantId)
//                .build();
//
//        AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
//        this.azureResourceManager = AzureResourceManager
//                .authenticate(credential, profile)
//                .withSubscription(subscriptionId);
//
//        this.logsQueryClient = new LogsQueryClientBuilder()
//                .credential(credential)
//                .buildClient();
//
//        this.metricsQueryClient = new MetricsQueryClientBuilder()
//                .credential(credential)
//                .buildClient();
//
//        this.workspaceId = workspaceId;
//    }
//
//    public void getLogsAndMetricsForAllVMs() {
//        List<VirtualMachine> vms = azureResourceManager.virtualMachines().list().stream().toList();
//
//        for (VirtualMachine vm : vms) {
//            String vmName = vm.name();
//            String resourceId = vm.id();
//
//            System.out.println("🔹 VM: " + vmName);
//            System.out.println("Resource ID: " + resourceId);
//
//            // Fetch Logs
//            String kql = String.format(
//                    "AzureDiagnostics | where Resource == '%s' | where TimeGenerated > ago(2h) | take 5",
//                    vmName
//            );
//            LogsQueryResult logResult = logsQueryClient.queryWorkspace(
//                    workspaceId,
//                    kql,
//                    Duration.ofMinutes(5)
//            );
//
//            // Fetch logs
//            logResult.getTable().getRows().forEach(row -> {
//                row.getColumnValues().forEach((col, val) -> System.out.println(col + ": " + val));
//                System.out.println("---");
//            });
//
//            // Fetch Metrics
//            MetricsQueryResult metricResult = metricsQueryClient.queryResource(
//                    resourceId,
//                    Arrays.asList("Percentage CPU"),
//                    new MetricsQueryOptions()
//                            .setGranularity(Duration.ofMinutes(5))
//                            .setTimespan(Duration.ofHours(1))
//            );
//
//            metricResult.getMetrics().forEach(metric -> {
//                System.out.println("Metric: " + metric.getName().getValue());
//                metric.getTimeSeries().forEach(series -> {
//                    series.getData().forEach(point -> {
//                        System.out.println("Time: " + point.getTimeStamp() + ", Value: " + point.getAverage());
//                    });
//                });
//            });
//
//            System.out.println("====================================================");
//        }
//    }
//
//    public static void main(String[] args) {
//        AzureVmLogsMetricsService service = new AzureVmLogsMetricsService(
//                "<your-client-id>",
//                "<your-client-secret>",
//                "<your-tenant-id>",
//                "<your-subscription-id>",
//                "<your-log-analytics-workspace-id>"
//        );
//
//        service.getLogsAndMetricsForAllVMs();
//    }
//}
