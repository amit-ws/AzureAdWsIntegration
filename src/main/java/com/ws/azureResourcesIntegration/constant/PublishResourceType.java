package com.ws.azureResourcesIntegration.constant;

import lombok.Getter;

@Getter
public enum PublishResourceType {
    // Azure Resources
    VIRTUAL_MACHINE("Virtual Machine"),
    DATABASE("Database"),
    STORAGE_ACCOUNT("Storage Account"),
    AZURE_KUBERNETES("Azure Kubernetes"),
    ROLE_DEFINITION("Role Definition"),

    // Kubernetes Resources
    NAMESPACE("Namespace"),
    CUSTOM_RESOURCE_DEFINITION("Custom Resource Definition"),
    NODE("Node"),
    STORAGE_CLASS("Storage Class"),
    PERSISTENT_VOLUME("Persistent Volume"),
    DEPLOYMENT("Deployment"),
    SERVICE_ACCOUNT("Service Account"),
    SECRET("Secret"),
    CONFIG_MAP("Config Map"),
    NETWORK_POLICY("Network Policy"),
    JOB("Job"),
    CRON_JOB("Cron Job"),
    INGRESS("Ingress"),
    SERVICE("Service"),
    REPLICA_SET("Replica Set"),
    STATEFUL_SET("Stateful Set"),
    DAEMON_SET("Daemon Set"),
    CLUSTER_ROLE("Cluster Role"),
    NAMESPACE_ROLE("Namespace Role");

    final String displayName;

    PublishResourceType(String displayName) {
        this.displayName = displayName;
    }
}
