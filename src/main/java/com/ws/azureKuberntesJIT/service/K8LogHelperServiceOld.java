package com.ws.azureKuberntesJIT.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.monitor.query.LogsQueryClient;
import com.azure.monitor.query.LogsQueryClientBuilder;
import com.azure.monitor.query.models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.azureKuberntesJIT.dto.K8sAuditLogAdvanced;
import com.ws.azureKuberntesJIT.dto.KubeAuditLog;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Component
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8LogHelperServiceOld {
    final String clientId = "cb51e8d1-519c-4e18-9b2f-28d53e6badd1";
    final String clientSecret = "TUo8Q~eVNZNHVbrGV4E8VLNphrJ24xObLUolOcJD";
    final String tenantId = "f875ebf8-f5f0-4915-a2c9-4442e0118fd2";
    final String workspaceId = "1ff52693-667a-48e8-a97a-9e8aa410c0d8";

    public List<KubeAuditLog> testLogs() {
        LogsQueryClient client = initializeAzureClient();
        String kqlQuery = "AzureDiagnostics " +
                "| where Category == \"kube-audit\" " +
                "| extend log_json = parse_json(log_s) " +
                "| extend PodName = tostring(log_json.user.extra[\"authentication.kubernetes.io/pod-name\"]) " +
                "| where tostring(log_json.user.username) == \"system:serviceaccount:test-nhi-ns:custom-sa\" " +
                "| project " +
                "TimeGenerated, " +
                "Namespace = tostring(log_json.objectRef.namespace), " +
                "PodName, " +
                "Verb = tostring(log_json.verb), " +
                "User = tostring(log_json.user.username), " +
                "Resource = tostring(log_json.objectRef.resource), " +
                "SourceIPs = tostring(log_json.sourceIPs), " +
                "ResponseStatus = tostring(log_json.responseStatus.reason), " +
                "RequestURI = tostring(log_json.requestURI) " +
                "| sort by TimeGenerated desc " +
                "| limit 50 ";

//        try {
//            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);
//            log.info("Query result status: {}", result.getQueryResultStatus().name());
//            log.info("Total tables: {}", result.getAllTables().size());
//            log.info("table: {}", result.getTable());
//            log.info("Total rows: {}", result.getTable().getRows().size());
//            log.info("Total columns: {}", result.getTable().getColumns().size());
//            log.info("Total cells: {}", result.getTable().getAllTableCells().size());
//            int columnCount = result.getTable().getRows().isEmpty() ? 0 : result.getTable().getRows().get(0).getRow().size();
//            List<LogsTableCell> cells = result.getTable().getRows().get(0).getRow();
//            for (LogsTableCell cell : cells) {
//                log.info("column name: {}", cell.getColumnName());
//            }
//
//            log.info("Column count from first row: {}", columnCount);
//        } catch (Exception ex) {
//            throw new RuntimeException(ex.getMessage());
//        }


        try {
            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);
//            long start = System.nanoTime();
//            List<KubeAuditLog> auditLogs = convertToKubeAuditLogs(result);
//            long durationMs = (System.nanoTime() - start) / 1_000_000;
//            log.info("durationMs - 1 : {}", durationMs);

//
//            log.info("-----------");
//            LogsTable table = result.getAllTables().get(0);
//            log.info("Total rows: {}", table.getRows().size());
//            log.info("Total columns: {}", table.getColumns().size());
//            log.info("Total cells: {}", table.getAllTableCells().size());
//            log.info("-----------");
//
//
//
//            // Now you can work with your POJOs
//            log.info("Converted {} log entries", auditLogs.size());
////            auditLogs.forEach(logEntry -> log.info("Log entry: {}", logEntry));
//            return auditLogs;

//            long start2 = System.nanoTime();
//            convertToKubeAuditLogsOptimized(result);
//            long durationMs2 = (System.nanoTime() - start2) / 1_000_000;
//            log.info("durationMs2 : {}", durationMs2);


//            long start3 = System.nanoTime();
            List<KubeAuditLog> kubeAuditLogs = convertToKubeAuditLogsUltraOptimized(result);
//            long durationMs3 = (System.nanoTime() - start3) / 1_000_000;
//            log.info("durationMs3: {}", durationMs3);


            return kubeAuditLogs;
        } catch (Exception ex) {
            throw new RuntimeException(ex.getMessage());
        }

    }


    public List<KubeAuditLog> convertToKubeAuditLogsUltraOptimized(LogsQueryResult result) {
        // Null check with zero allocation
        if (result == null || result.getAllTables().isEmpty()) {
            return List.of();
        }

        // Array conversion (faster iteration)
        LogsTableRow[] rows = result.getTable().getRows().toArray(LogsTableRow[]::new);
        if (rows.length == 0) {
            return List.of();
        }

        // Column indices (array beats objects for speed)
        int[] cols = new int[9]; // 9 columns
        Arrays.fill(cols, -1); // -1 = not found

        // Single-pass column mapping
        LogsTableCell[] firstRowCells = rows[0].getRow().toArray(LogsTableCell[]::new);
        for (int i = 0; i < firstRowCells.length; i++) {
            switch (firstRowCells[i].getColumnName()) {
                case "TimeGenerated":
                    cols[0] = i;
                    break;
                case "Namespace":
                    cols[1] = i;
                    break;
                case "PodName":
                    cols[2] = i;
                    break;
                case "Verb":
                    cols[3] = i;
                    break;
                case "User":
                    cols[4] = i;
                    break;
                case "Resource":
                    cols[5] = i;
                    break;
                case "SourceIPs":
                    cols[6] = i;
                    break;
                case "ResponseStatus":
                    cols[7] = i;
                    break;
                case "RequestURI":
                    cols[8] = i;
                    break;
            }
        }

        // Parallel processing with direct array access
        return Arrays.stream(rows)
                .parallel()
                .map(row -> {
                    LogsTableCell[] cells = row.getRow().toArray(LogsTableCell[]::new);
                    KubeAuditLog log = new KubeAuditLog();

                    // TimeGenerated
                    if (cols[0] != -1 && cols[0] < cells.length) {
                        Object val = cells[cols[0]].getValueAsString();
                        log.setTimeGenerated(val != null ? Instant.parse(val.toString()) : null);
                    }

                    // Namespace
                    if (cols[1] != -1 && cols[1] < cells.length) {
                        log.setNamespace(toString(cells[cols[1]]));
                    }

                    // PodName
                    if (cols[2] != -1 && cols[2] < cells.length) {
                        log.setPodName(toString(cells[cols[2]]));
                    }

                    // Verb
                    if (cols[3] != -1 && cols[3] < cells.length) {
                        log.setVerb(toString(cells[cols[3]]));
                    }

                    // User
                    if (cols[4] != -1 && cols[4] < cells.length) {
                        log.setUser(toString(cells[cols[4]]));
                    }

                    // Resource
                    if (cols[5] != -1 && cols[5] < cells.length) {
                        log.setResource(toString(cells[cols[5]]));
                    }

                    // SourceIPs
                    if (cols[6] != -1 && cols[6] < cells.length) {
                        log.setSourceIPs(toString(cells[cols[6]]));
                    }

                    // ResponseStatus
                    if (cols[7] != -1 && cols[7] < cells.length) {
                        log.setResponseStatus(toString(cells[cols[7]]));
                    }

                    // RequestURI
                    if (cols[8] != -1 && cols[8] < cells.length) {
                        log.setRequestURI(toString(cells[cols[8]]));
                    }

                    return log;
                })
                .collect(Collectors.toList());
    }

    // Ultra-fast null-safe toString
    private String toString(LogsTableCell cell) {
        Object val = cell.getValueAsString();
        return val != null ? val.toString() : null;
    }


    private List<KubeAuditLog> convertToKubeAuditLogsOptimized(LogsQueryResult result) {
        List<KubeAuditLog> auditLogs = new ArrayList<>();

        if (result == null || result.getAllTables().isEmpty()) {
            return auditLogs;
        }

        LogsTable table = result.getTable();
        List<LogsTableRow> rows = table.getRows();

        if (rows.isEmpty()) {
            return auditLogs;
        }

        // Pre-determine column positions from first row
        List<LogsTableCell> firstRowCells = rows.get(0).getRow();
        int timeGeneratedIdx = -1;
        int namespaceIdx = -1;
        int podNameIdx = -1;
        int verbIdx = -1;
        int userIdx = -1;
        int resourceIdx = -1;
        int sourceIPsIdx = -1;
        int responseStatusIdx = -1;
        int requestURIIdx = -1;

        // Find column indices once
        for (int i = 0; i < firstRowCells.size(); i++) {
            String columnName = firstRowCells.get(i).getColumnName();
            switch (columnName) {
                case "TimeGenerated":
                    timeGeneratedIdx = i;
                    break;
                case "Namespace":
                    namespaceIdx = i;
                    break;
                case "PodName":
                    podNameIdx = i;
                    break;
                case "Verb":
                    verbIdx = i;
                    break;
                case "User":
                    userIdx = i;
                    break;
                case "Resource":
                    resourceIdx = i;
                    break;
                case "SourceIPs":
                    sourceIPsIdx = i;
                    break;
                case "ResponseStatus":
                    responseStatusIdx = i;
                    break;
                case "RequestURI":
                    requestURIIdx = i;
                    break;
            }
        }

        // Process all rows in a single loop
        for (LogsTableRow row : rows) {
            List<LogsTableCell> cells = row.getRow();

            KubeAuditLog logEntry = new KubeAuditLog();

            if (timeGeneratedIdx != -1 && cells.size() > timeGeneratedIdx) {
                Object value = cells.get(timeGeneratedIdx).getValueAsString();
                logEntry.setTimeGenerated(value != null ? Instant.parse(value.toString()) : null);
            }
            if (namespaceIdx != -1 && cells.size() > namespaceIdx) {
                logEntry.setNamespace(getStringValue(cells, namespaceIdx));
            }
            if (podNameIdx != -1 && cells.size() > podNameIdx) {
                logEntry.setPodName(getStringValue(cells, podNameIdx));
            }
            if (verbIdx != -1 && cells.size() > verbIdx) {
                logEntry.setVerb(getStringValue(cells, verbIdx));
            }
            if (userIdx != -1 && cells.size() > userIdx) {
                logEntry.setUser(getStringValue(cells, userIdx));
            }
            if (resourceIdx != -1 && cells.size() > resourceIdx) {
                logEntry.setResource(getStringValue(cells, resourceIdx));
            }
            if (sourceIPsIdx != -1 && cells.size() > sourceIPsIdx) {
                logEntry.setSourceIPs(getStringValue(cells, sourceIPsIdx));
            }
            if (responseStatusIdx != -1 && cells.size() > responseStatusIdx) {
                logEntry.setResponseStatus(getStringValue(cells, responseStatusIdx));
            }
            if (requestURIIdx != -1 && cells.size() > requestURIIdx) {
                logEntry.setRequestURI(getStringValue(cells, requestURIIdx));
            }

            auditLogs.add(logEntry);
        }

        return auditLogs;
    }

    private String getStringValue(List<LogsTableCell> cells, int index) {
        Object value = cells.get(index).getValueAsString();
        return value != null ? value.toString() : null;
    }


    public List<KubeAuditLog> convertToKubeAuditLogs(LogsQueryResult result) {
        List<KubeAuditLog> auditLogs = new ArrayList<>();

        if (result == null || result.getAllTables().isEmpty()) {
            return auditLogs;
        }

        LogsTable table = result.getTable();
        List<LogsTableRow> rows = table.getRows();

        if (rows.isEmpty()) {
            return auditLogs;
        }

        for (LogsTableRow row : rows) {
            List<LogsTableCell> cells = row.getRow();

            KubeAuditLog logEntry = new KubeAuditLog();

            for (LogsTableCell cell : cells) {
                String columnName = cell.getColumnName();
                Object value = cell.getValueAsString();

                switch (columnName) {
                    case "TimeGenerated":
                        if (value != null) {
                            logEntry.setTimeGenerated(Instant.parse(value.toString()));
                        }
                        break;
                    case "Namespace":
                        logEntry.setNamespace(value != null ? value.toString() : null);
                        break;
                    case "PodName":
                        logEntry.setPodName(value != null ? value.toString() : null);
                        break;
                    case "Verb":
                        logEntry.setVerb(value != null ? value.toString() : null);
                        break;
                    case "User":
                        logEntry.setUser(value != null ? value.toString() : null);
                        break;
                    case "Resource":
                        logEntry.setResource(value != null ? value.toString() : null);
                        break;
                    case "SourceIPs":
                        logEntry.setSourceIPs(value != null ? value.toString() : null);
                        break;
                    case "ResponseStatus":
                        logEntry.setResponseStatus(value != null ? value.toString() : null);
                        break;
                    case "RequestURI":
                        logEntry.setRequestURI(value != null ? value.toString() : null);
                        break;
                    default:
                        break;
                }
            }

            auditLogs.add(logEntry);
        }

        return auditLogs;
    }


    public List<K8sAuditLogAdvanced> runAzureLogAnalyticsQuery(String kqlQuery) {
        LogsQueryClient client = initializeAzureClient();

        try {
            LogsQueryResult result = client.queryWorkspace(workspaceId, kqlQuery, QueryTimeInterval.LAST_DAY);
            log.info("Query result status: {}", result.getQueryResultStatus().name());

            if (result.getTable() == null) {
                throw new RuntimeException("No result table returned from query.");
            }

            LogsTable table = result.getTable();
            log.info("Retrieved {} rows with {} columns", table.getRows().size(), table.getColumns().size());
            log.info("Found {} rows with columns: {}",
                    table.getRows().size(),
                    table.getColumns().stream().map(LogsTableColumn::getColumnName).collect(Collectors.joining(", "))
            );

            log.info("total columns: {}", table.getColumns().size());


            // Create column name to index mapping
            Map<String, Integer> columnMap = new HashMap<>();
            for (int i = 0; i < table.getColumns().size(); i++) {
                columnMap.put(table.getColumns().get(i).getColumnName(), i);
            }

            // Transform rows to POJOs
            List<K8sAuditLogAdvanced> logs = new ArrayList<>();
            ObjectMapper objectMapper = new ObjectMapper();

            for (LogsTableRow row : table.getRows()) {
                try {
                    K8sAuditLogAdvanced logEntry = new K8sAuditLogAdvanced();

                    // Simple string fields
                    logEntry.setTimeGenerated(getRowValue(row, columnMap, "TimeGenerated", String.class));
                    logEntry.setNamespace(getRowValue(row, columnMap, "Namespace", String.class));
                    logEntry.setPodName(getRowValue(row, columnMap, "PodName", String.class));
                    logEntry.setVerb(getRowValue(row, columnMap, "Verb", String.class));
                    logEntry.setUser(getRowValue(row, columnMap, "User", String.class));
                    logEntry.setUserUID(getRowValue(row, columnMap, "UserUID", String.class));
                    logEntry.setResource(getRowValue(row, columnMap, "Resource", String.class));
                    logEntry.setSubResource(getRowValue(row, columnMap, "SubResource", String.class));
                    logEntry.setResourceName(getRowValue(row, columnMap, "ResourceName", String.class));
                    logEntry.setRequestURI(getRowValue(row, columnMap, "RequestURI", String.class));
                    logEntry.setUserAgent(getRowValue(row, columnMap, "UserAgent", String.class));
                    logEntry.setResponseStatusReason(getRowValue(row, columnMap, "ResponseStatusReason", String.class));
                    logEntry.setStage(getRowValue(row, columnMap, "Stage", String.class));
                    logEntry.setAnnotations(getRowValue(row, columnMap, "Annotations", String.class));
                    logEntry.setAuditID(getRowValue(row, columnMap, "AuditID", String.class));

                    // Complex fields that need parsing
                    logEntry.setUserGroups(parseJsonArray(getRowValue(row, columnMap, "UserGroups", String.class), objectMapper));
                    logEntry.setSourceIPs(parseJsonArray(getRowValue(row, columnMap, "SourceIPs", String.class), objectMapper));

                    // Numeric field
                    logEntry.setResponseStatusCode(getRowValue(row, columnMap, "ResponseStatusCode", Integer.class));

                    // DateTime field
                    String timestampStr = getRowValue(row, columnMap, "RequestReceivedTimestamp", String.class);
                    if (timestampStr != null) {
                        logEntry.setRequestReceivedTimestamp(OffsetDateTime.parse(timestampStr));
                    }

                    // JSON object fields
                    logEntry.setRequestObject(parseJsonObject(getRowValue(row, columnMap, "RequestObjectStr", String.class), objectMapper));
                    logEntry.setResponseObject(parseJsonObject(getRowValue(row, columnMap, "ResponseObjectStr", String.class), objectMapper));

                    logs.add(logEntry);
                } catch (Exception e) {
                    log.warn("Failed to parse row: {}", e.getMessage());
                }
            }

            if (logs.isEmpty()) {
                throw new RuntimeException("No valid log entries found after parsing.");
            }

            log.info("Successfully parsed {} log entries", logs.size());
            return logs;

        } catch (Exception e) {
            log.error("Error running KQL query: {}", e.getMessage(), e);
            throw new RuntimeException("Error running KQL query: " + e.getMessage(), e);
        }
    }

    // Generic method to get typed values from LogsTableRow
    private <T> T getRowValue(LogsTableRow row, Map<String, Integer> columnMap, String columnName, Class<T> type) {
        Integer index = columnMap.get(columnName);
        if (index == null || index >= row.getRow().size()) {
            return null;
        }
        Object value = row.getRow().get(index);
        return type.cast(value);
    }

    // JSON parsing helpers remain the same as previous version
    @SuppressWarnings("unchecked")
    private List<String> parseJsonArray(String jsonString, ObjectMapper objectMapper) {
        if (jsonString == null || jsonString.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(jsonString, List.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON array: {}", jsonString);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String jsonString, ObjectMapper objectMapper) {
        if (jsonString == null || jsonString.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(jsonString, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON object: {}", jsonString);
            return Collections.emptyMap();
        }
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
