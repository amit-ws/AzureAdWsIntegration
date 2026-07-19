package com.ws.logMcpServer.service;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.monitor.query.LogsQueryClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.MetricsQueryClient;
import com.azure.monitor.query.MetricsQueryClientBuilder;
import com.azure.monitor.query.models.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;

@Service
@Slf4j
public class AzureResourcesLogsMetricsService {
    private final LogsQueryClient logsQueryClient;
//    private final String workspaceId = "1ff52693-667a-48e8-a97a-9e8aa410c0d8";
//    private final MetricsQueryClient metricsQueryClient;


    public AzureResourcesLogsMetricsService() {

        TokenCredential credential = new ClientSecretCredentialBuilder()
                .clientId("cb51e8d1-519c-4e18-9b2f-28d53e6badd1")
                .clientSecret("TUo8Q~eVNZNHVbrGV4E8VLNphrJ24xObLUolOcJD")
                .tenantId("f875ebf8-f5f0-4915-a2c9-4442e0118fd2")
                .build();

        this.logsQueryClient = new LogsQueryClientBuilder()
                .credential(credential)
                .buildClient();

//        this.metricsQueryClient = new MetricsQueryClientBuilder()
//                .credential(credential)
//                .buildClient();
    }


    public void getLogsForAllVMs() {
//        String kqlQuery = """
//                AzureDiagnostics
//                | where ResourceType == "VIRTUAL_MACHINE"
//                | where TimeGenerated > ago(1h)
//                | top 10 by TimeGenerated desc
//                """;

//        QueryTimeInterval queryTimeInterval = new QueryTimeInterval(Duration.ofDays(1));

        String kqlQuery = "AzureDiagnostics | where ResourceType == 'VIRTUAL_MACHINE' | top 10 by TimeGenerated desc";
        LogsQueryResult result = logsQueryClient.queryWorkspace("1ff52693-667a-48e8-a97a-9e8aa410c0d8", kqlQuery, QueryTimeInterval.LAST_7_DAYS);

//        log.info("tables: {}", result.getAllTables().size());
//        log.info("cols: {}", result.getAllTables().get(0).getColumns().size());
//
//        for (LogsTableColumn column : result.getAllTables().get(0).getColumns()) {
//             log.info("col name: {}", column.getColumnName());
//             log.info("col type: {}", column.getColumnType());
//        }

        result.getAllTables().forEach(table ->
                table.getRows().forEach(row ->
                        table.getColumns().forEach(column -> {
                            handleColumnData(row, column);
                        })
                )
        );
    }


    private void handleColumnData(LogsTableRow row, LogsTableColumn column) {
        String columnName = column.getColumnName();
        Object columnValue = row.getColumnValue(columnName);
        String columnType = column.getColumnType().getValue();

        LogColumnData logColumnData = LogColumnData.builder()
                .columnName(columnName)
                .columnValue(columnValue)
                .columnType(columnType)
                .build();

        log.info("logColumnData: {}", logColumnData);
    }


    //    public void getMetricsForAllVMs() {
//        // KQL query is not needed for metrics (you can customize the query as per Azure's metrics API)
//        String metricName = "Percentage CPU"; // Example: CPU usage metric for VMs
//        String resourceUri = "/subscriptions/<your_subscription_id>/resourceGroups/<your_resource_group>/providers/Microsoft.Compute/virtualMachines"; // Replace with your actual resource URI
//
//        // Define the time range for metrics (e.g., last 1 hour)
//        QueryTimeInterval timeInterval = QueryTimeInterval.LAST_1_HOUR;
//
//        // Query using the MetricsQueryClient (use the appropriate metric namespace or resource URI)
//        MetricsQueryResult result = metricsQueryClient.queryMetrics(
//                workspaceId,
//                resourceUri,
//                metricName,
//                timeInterval
//        );
//
//        for (MetricResult metric : result.getMetrics()) {
//            metric.get
//        }
//
//        // Process and display the results
//        result.getMetrics().forEach(table -> {
//            table.getRows().forEach(row -> {
//                table.getColumns().forEach(column -> {
//                    String columnName = column.getColumnName();
//                    Object columnValue = row.getColumnValue(columnName);
//                    String columnType = column.getColumnType().getValue();
//
//                    // You can store or process the log data further as needed
//                    System.out.println("Metric Data - " + columnName + ": " + columnValue + " (" + columnType + ")");
//                });
//                System.out.println("---");
//            });
//        });
//    }
}
