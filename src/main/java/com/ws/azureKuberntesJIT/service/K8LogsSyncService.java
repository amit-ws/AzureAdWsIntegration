//package com.ws.azureKuberntesJIT.service;
//
//import com.ws.azureKuberntesJIT.dto.K8sLogEntryDTO;
//import io.kubernetes.client.openapi.ApiException;
//import io.kubernetes.client.openapi.models.V1Container;
//import io.kubernetes.client.openapi.models.V1Node;
//import io.kubernetes.client.openapi.models.V1PodList;
//
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.stream.Collectors;
//import java.util.stream.Stream;
//
//public class K8LogsSyncService {
//
//    private List<K8sLogEntryDTO> fetchNhiLogs(String clusterId) {
//        Map<String, V1Node> nodeCache = new ConcurrentHashMap<>();
//
//        try {
//            V1PodList podList = this.coreV1Api.listPodForAllNamespaces().execute();
//
//            if (podList == null || podList.getItems().isEmpty()) {
//                log.warn("No pods found for cluster: {}", clusterId);
//                return Collections.emptyList();
//            }
//
//            // ParallelStream, each pod processed independently
//            return podList.getItems().parallelStream()
//                    .filter(pod -> {
//                        String serviceAccount = pod.getSpec().getServiceAccountName();
//                        return serviceAccount != null && !serviceAccount.equals("default");
//                    })
//                    .flatMap(pod -> {
//                        List<K8sLogEntryDTO> localEntries = new ArrayList<>();
//                        try {
//                            String namespace = pod.getMetadata().getNamespace();
//                            String podName = pod.getMetadata().getName();
//                            String nodeName = pod.getSpec().getNodeName();
//                            String serviceAccount = pod.getSpec().getServiceAccountName();
//                            Map<String, String> podLabels = pod.getMetadata().getLabels();
//
//                            V1Node node = nodeCache.computeIfAbsent(nodeName, n -> {
//                                try {
//                                    return this.coreV1Api.readNode(n).execute();
//                                } catch (ApiException e) {
//                                    log.warn("Failed to fetch node metadata for cluster {}, node {}: {}", clusterId, nodeName, e.getMessage());
//                                    return null;
//                                }
//                            });
//
//                            if (node == null) return Stream.empty();
//
//                            Map<String, String> nodeLabels = node.getMetadata().getLabels();
//
//                            for (V1Container container : pod.getSpec().getContainers()) {
//                                try {
//                                    String rawLog = this.coreV1Api.readNamespacedPodLog(podName, namespace)
//                                            .container(container.getName())
//                                            .timestamps(true)
//                                            .execute();
//
//                                    localEntries.addAll(parseRawLogsToDTOs(rawLog, clusterId, namespace, podName,
//                                            container.getName(), serviceAccount, nodeName, podLabels, nodeLabels));
//
//                                } catch (Exception logErr) {
//                                    log.warn("Log fetch failure for {} / {} / {}: {}", namespace, podName, container.getName(), logErr.getMessage());
//                                }
//                            }
//                        } catch (Exception e) {
//                            log.warn("Error processing pod {}: {}", pod.getMetadata().getName(), e.getMessage());
//                        }
//                        return localEntries.stream();
//                    })
//                    .collect(Collectors.toList());
//
//        } catch (Exception e) {
//            log.error("Critical error fetching logs for cluster {}: {}", clusterId, e.getMessage(), e);
//            return Collections.emptyList();
//        }
//    }
//
//
//    private List<K8sLogEntryDTO> parseRawLogsToDTOs(String rawLogs, String clusterId, String namespace,
//                                                    String podName, String containerName,
//                                                    String serviceAccount, String nodeName,
//                                                    Map<String, String> podLabels, Map<String, String> nodeLabels) {
//        List<K8sLogEntryDTO> entries = new ArrayList<>();
//
//        for (String line : rawLogs.split("\n")) {
//            if (line.isBlank()) continue;
//            String[] parts = line.split(" ", 2);
//            String timestamp = parts[0];
//            String message = parts.length > 1 ? parts[1] : "";
//
//            K8sLogEntryDTO entry = new K8sLogEntryDTO();
//            entry.setClusterId(clusterId);
//            entry.setNamespace(namespace);
//            entry.setPodName(podName);
//            entry.setContainerName(containerName);
//            entry.setServiceAccount(serviceAccount);
//            entry.setNodeName(nodeName);
//            entry.setTimestamp(timestamp);
//            entry.setMessage(message);
//            entry.setPodLabels(podLabels);
//            entry.setNodeLabels(nodeLabels);
//            entry.setLogLevel(extractLogLevel(message)); // heuristic
//
//            entries.add(entry);
//        }
//
//        return entries;
//    }
//
//    private String extractLogLevel(String message) {
//        if (message == null) return "UNKNOWN";
//        String msg = message.toLowerCase(Locale.ROOT);
//
//        if (msg.contains("error")) return "ERROR";
//        if (msg.contains("warn")) return "WARN";
//        if (msg.contains("debug")) return "DEBUG";
//        if (msg.contains("info")) return "INFO";
//        return "INFO";
//    }
//}
