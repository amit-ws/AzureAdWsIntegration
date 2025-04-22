package com.ws.azureKuberntesJIT.constant;


import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum K8ResourceType {
    //    NAMESPACE, NODE, DEPLOYMENT, SERVICE_ACCOUNT, SECRET, CONFIG_MAP, PERSISTENT_VOLUME, PERSISTENT_VOLUME_CLAIM,
//    STORAGE_CLASS, CUSTOM_RESOURCE_DEFINITION, CLUSTER_ROLE, CLUSTER_ROLE_BINDING, NAMESPACE_ROLE, NAMESPACE_ROLE_BINDING, NETWORK_POLICY,
//    JOB, CRON_JOB, INGRESS, SERVICE, REPLICA_SET, STATEFUL_SET, DAEMON_SET;

    // Core API Group (empty string for the API group)
    POD("Pod", "pods", "v1", "", "Represents a Pod in Kubernetes"),
    SERVICE("Service", "services", "v1", "", "Represents a Service in Kubernetes"),
    NAMESPACE("Namespace", "namespaces", "v1", "", "Represents a Namespace in Kubernetes"),
    NODE("Node", "nodes", "v1", "", "Represents a Node in Kubernetes"),
    SECRET("Secret", "secrets", "v1", "", "Represents a Secret in Kubernetes"),
    CONFIG_MAP("Config Map", "configmaps", "v1", "", "Represents a ConfigMap in Kubernetes"),
    PERSISTENT_VOLUME("Persistent Volume", "persistentvolumes", "v1", "", "Represents a PersistentVolume in Kubernetes"),
    PERSISTENT_VOLUME_CLAIM("Persistent Volume Claim", "persistentvolumeclaims", "v1", "", "Represents a PersistentVolumeClaim in Kubernetes"),
    ENDPOINT("Endpoint", "endpoints", "v1", "", "Represents an Endpoint in Kubernetes"),
    SERVICE_ACCOUNT("Service Account", "serviceaccounts", "v1", "", "Represents a ServiceAccount in Kubernetes"),
    REPLICATION_CONTROLLER("Replication Controller", "replicationcontrollers", "v1", "", "Represents a ReplicationController in Kubernetes"),

    // Apps API Group (apps/v1)
    DEPLOYMENT("Deployment", "deployments", "apps/v1", "apps", "Represents a Deployment in Kubernetes"),
    STATEFUL_SET("Stateful Set", "statefulsets", "apps/v1", "apps", "Represents a StatefulSet in Kubernetes"),
    REPLICA_SET("Replica Set", "replicasets", "apps/v1", "apps", "Represents a ReplicaSet in Kubernetes"),
    DAEMON_SET("Daemon Set", "daemonsets", "apps/v1", "apps", "Represents a DaemonSet in Kubernetes"),

    // RBAC API Group (rbac.authorization.k8s.io)
    NAMESPACE_ROLE("Role", "roles", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a Role in Kubernetes RBAC"),
    CLUSTER_ROLE("Cluster Role", "clusterroles", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a ClusterRole in Kubernetes RBAC"),
    ROLE_BINDING("Role Binding", "rolebindings", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a RoleBinding in Kubernetes RBAC"),
    CLUSTER_ROLE_BINDING("Cluster Role Binding", "clusterrolebindings", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a ClusterRoleBinding in Kubernetes RBAC"),

    // Networking API Group (networking.k8s.io)
    INGRESS("Ingress", "ingresses", "networking.k8s.io/v1", "networking.k8s.io", "Represents an Ingress resource in Kubernetes"),
    NETWORK_POLICY("Network Policy", "networkpolicies", "networking.k8s.io/v1", "networking.k8s.io", "Represents a NetworkPolicy resource in Kubernetes"),

    // Storage API Group (storage.k8s.io)
    STORAGE_CLASS("Storage Class", "storageclasses", "storage.k8s.io/v1", "storage.k8s.io", "Represents a StorageClass in Kubernetes"),
    VOLUME_ATTACHMENT("Volume Attachment", "volumeattachments", "storage.k8s.io/v1", "storage.k8s.io", "Represents a VolumeAttachment in Kubernetes"),

    // Batch API Group (batch/v1)
    JOB("Job", "jobs", "batch/v1", "batch", "Represents a Job in Kubernetes"),
    CRON_JOB("Cron Job", "cronjobs", "batch/v1", "batch", "Represents a CronJob in Kubernetes"),

    // Events API Group (events.k8s.io)
    EVENT("Event", "events", "events.k8s.io/v1", "events.k8s.io", "Represents an Event in Kubernetes"),

    // Additional Resource Types (commonly used)
    RESOURCEQUOTA("Resource Quota", "resourcequotas", "v1", "", "Represents a ResourceQuota in Kubernetes"),
    LIMITRANGE("Limit Range", "limitranges", "v1", "", "Represents a LimitRange in Kubernetes"),
    HORIZONTALPODAUTOSCALER("Horizontal PodAuto scaler", "horizontalpodautoscalers", "autoscaling/v1", "autoscaling", "Represents a HorizontalPodAutoscaler in Kubernetes"),

    // Custom Resource Definition (CRD) entry for the enum
    CUSTOM_RESOURCE_DEFINITION("Custom Resource Definition", "customresourcedefinitions", "apiextensions.k8s.io/v1", "apiextensions.k8s.io", "Represents a CustomResourceDefinition (CRD) in Kubernetes, allowing for custom resource types");


    final String displayName;
    final String resourceName;
    final String apiVersion;
    final String apiGroup;
    final String description;

    K8ResourceType(String displayName, String resourceName, String apiVersion, String apiGroup, String description) {
        this.displayName = displayName;
        this.resourceName = resourceName;
        this.apiVersion = apiVersion;
        this.apiGroup = apiGroup;
        this.description = description;
    }

    @Override
    public String toString() {
        return String.format("Resource: %s, API Version: %s, API Group: %s, Description: %s", resourceName, apiVersion, apiGroup, description);
    }
}

