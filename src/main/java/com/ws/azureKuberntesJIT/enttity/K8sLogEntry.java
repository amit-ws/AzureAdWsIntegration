package com.ws.azureKuberntesJIT.enttity;


import com.ws.azureAdIntegration.constants.CloudProviderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Date;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_logs_entry", schema = "azure_test")
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class K8sLogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false)
    String clusterId;
    String clusterName;

    String namespace;
    String podName;
    String containerName;
    String serviceAccount;
    String nodeName;
    String rawLog;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "kubernetes_log_node_labels")
    @MapKeyColumn(name = "label_key")
    @Column(name = "label_value")
    Map<String, String> nodeLabels;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "kubernetes_log_pod_labels")
    @MapKeyColumn(name = "label_key")
    @Column(name = "label_value")
    Map<String, String> podLabels;

    String logLevel;
    String timestamp; // Timestamp from the K8 Logs
    String message;


    Date createdAt;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    CloudProviderType cloudProviderType;
    @Column(nullable = false)
    String cloudResourceAccountId; /* for Azure = subscription_id.  For GCP = Project_id*/
    @Column(nullable = false)
    String wsTenantName;
}
