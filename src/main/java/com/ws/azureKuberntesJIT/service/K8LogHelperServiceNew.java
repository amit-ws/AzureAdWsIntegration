package com.ws.azureKuberntesJIT.service;

import com.azure.monitor.query.models.LogsQueryResult;
import com.azure.monitor.query.models.LogsTableCell;
import com.azure.monitor.query.models.LogsTableRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.azureKuberntesJIT.dto.K8sAuditLogAdvanced;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8LogHelperServiceNew {

    public List<K8sAuditLogAdvanced> convertToK8sAuditLogsAdvancedOptimized(LogsQueryResult result) {
        // Early exit checks
        if (result == null || result.getAllTables().isEmpty()) {
            return List.of();
        }

        // Array conversion for faster iteration
        LogsTableRow[] rows = result.getTable().getRows().toArray(LogsTableRow[]::new);
        if (rows.length == 0) {
            return List.of();
        }

        // Column indices (using array for speed)
        int[] cols = new int[21]; // Total columns we need to map
        Arrays.fill(cols, -1); // -1 indicates column not found

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
                case "UserUID":
                    cols[5] = i;
                    break;
                case "UserGroups":
                    cols[6] = i;
                    break;
                case "Resource":
                    cols[7] = i;
                    break;
                case "SubResource":
                    cols[8] = i;
                    break;
                case "ResourceName":
                    cols[9] = i;
                    break;
                case "RequestURI":
                    cols[10] = i;
                    break;
                case "SourceIPs":
                    cols[11] = i;
                    break;
                case "UserAgent":
                    cols[12] = i;
                    break;
                case "ResponseStatusCode":
                    cols[13] = i;
                    break;
                case "ResponseStatusReason":
                    cols[14] = i;
                    break;
                case "Stage":
                    cols[15] = i;
                    break;
                case "Annotations":
                    cols[16] = i;
                    break;
                case "RequestReceivedTimestamp":
                    cols[17] = i;
                    break;
                case "AuditID":
                    cols[18] = i;
                    break;
                case "RequestObjectStr":
                    cols[19] = i;
                    break;
                case "ResponseObjectStr":
                    cols[20] = i;
                    break;
            }
        }

        // Reuse this parser to avoid creating new instances
        ObjectMapper objectMapper = new ObjectMapper();

        return Arrays.stream(rows)
                .parallel()
                .map(row -> {
                    LogsTableCell[] cells = row.getRow().toArray(LogsTableCell[]::new);
                    K8sAuditLogAdvanced log = new K8sAuditLogAdvanced();

                    // TimeGenerated
                    if (cols[0] != -1 && cols[0] < cells.length) {
                        log.setTimeGenerated(toString(cells[cols[0]]));
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

                    // UserUID
                    if (cols[5] != -1 && cols[5] < cells.length) {
                        log.setUserUID(toString(cells[cols[5]]));
                    }

                    // UserGroups (JSON array)
                    if (cols[6] != -1 && cols[6] < cells.length) {
                        String groupsStr = toString(cells[cols[6]]);
                        if (groupsStr != null && !groupsStr.isEmpty()) {
                            try {
                                log.setUserGroups(objectMapper.readValue(groupsStr, List.class));
                            } catch (JsonProcessingException e) {
                                log.setUserGroups(List.of());
                            }
                        }
                    }

                    // Resource
                    if (cols[7] != -1 && cols[7] < cells.length) {
                        log.setResource(toString(cells[cols[7]]));
                    }

                    // SubResource
                    if (cols[8] != -1 && cols[8] < cells.length) {
                        log.setSubResource(toString(cells[cols[8]]));
                    }

                    // ResourceName
                    if (cols[9] != -1 && cols[9] < cells.length) {
                        log.setResourceName(toString(cells[cols[9]]));
                    }

                    // RequestURI
                    if (cols[10] != -1 && cols[10] < cells.length) {
                        log.setRequestURI(toString(cells[cols[10]]));
                    }

                    // SourceIPs (JSON array)
                    if (cols[11] != -1 && cols[11] < cells.length) {
                        String ipsStr = toString(cells[cols[11]]);
                        if (ipsStr != null && !ipsStr.isEmpty()) {
                            try {
                                log.setSourceIPs(objectMapper.readValue(ipsStr, List.class));
                            } catch (JsonProcessingException e) {
                                log.setSourceIPs(List.of());
                            }
                        }
                    }

                    // UserAgent
                    if (cols[12] != -1 && cols[12] < cells.length) {
                        log.setUserAgent(toString(cells[cols[12]]));
                    }

                    // ResponseStatusCode
                    if (cols[13] != -1 && cols[13] < cells.length) {
                        Object val = cells[cols[13]].getValueAsDynamic();
                        if (val instanceof Number) {
                            log.setResponseStatusCode(((Number) val).intValue());
                        } else if (val != null) {
                            try {
                                log.setResponseStatusCode(Integer.parseInt(val.toString()));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }

                    // ResponseStatusReason
                    if (cols[14] != -1 && cols[14] < cells.length) {
                        log.setResponseStatusReason(toString(cells[cols[14]]));
                    }

                    // Stage
                    if (cols[15] != -1 && cols[15] < cells.length) {
                        log.setStage(toString(cells[cols[15]]));
                    }

//                    // Annotations
//                    if (cols[16] != -1 && cols[16] < cells.length) {
//                        log.setAnnotations(toString(cells[cols[16]]));
//                    }

                    // Annotations (JSON object)
                    if (cols[16] != -1 && cols[16] < cells.length) {
                        String annotationsStr = toString(cells[cols[16]]);
                        if (annotationsStr != null && !annotationsStr.isEmpty()) {
                            try {
                                log.setAnnotationObj(objectMapper.readValue(annotationsStr, Map.class));
                            } catch (JsonProcessingException e) {
                                log.setAnnotationObj(Map.of());
                            }
                        } else {
                            log.setAnnotationObj(Map.of());
                        }
                    }

                    // RequestReceivedTimestamp
                    if (cols[17] != -1 && cols[17] < cells.length) {
                        Object val = cells[cols[17]].getValueAsString();
                        if (val != null) {
                            try {
                                log.setRequestReceivedTimestamp(OffsetDateTime.parse(val.toString()));
                            } catch (DateTimeParseException ignored) {
                            }
                        }
                    }

                    // AuditID
                    if (cols[18] != -1 && cols[18] < cells.length) {
                        log.setAuditID(toString(cells[cols[18]]));
                    }

                    // RequestObject (JSON object)
                    if (cols[19] != -1 && cols[19] < cells.length) {
                        String requestObjStr = toString(cells[cols[19]]);
                        if (requestObjStr != null && !requestObjStr.isEmpty()) {
                            try {
                                log.setRequestObject(objectMapper.readValue(requestObjStr, Map.class));
                            } catch (JsonProcessingException e) {
                                log.setRequestObject(Map.of());
                            }
                        }
                    }

                    // ResponseObject (JSON object)
                    if (cols[20] != -1 && cols[20] < cells.length) {
                        String responseObjStr = toString(cells[cols[20]]);
                        if (responseObjStr != null && !responseObjStr.isEmpty()) {
                            try {
                                log.setResponseObject(objectMapper.readValue(responseObjStr, Map.class));
                            } catch (JsonProcessingException e) {
                                log.setResponseObject(Map.of());
                            }
                        }
                    }

                    return log;
                })
                .collect(Collectors.toList());
    }

    // Reused from previous implementation
    private String toString(LogsTableCell cell) {
        Object val = cell.getValueAsString();
        return val != null ? val.toString() : null;
    }
}
