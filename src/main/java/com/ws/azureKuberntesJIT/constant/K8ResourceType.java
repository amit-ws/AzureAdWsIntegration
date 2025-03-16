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
    POD("pods", "v1", "", "Represents a Pod in Kubernetes"),
    SERVICE("services", "v1", "", "Represents a Service in Kubernetes"),
    NAMESPACE("namespaces", "v1", "", "Represents a Namespace in Kubernetes"),
    NODE("nodes", "v1", "", "Represents a Node in Kubernetes"),
    SECRET("secrets", "v1", "", "Represents a Secret in Kubernetes"),
    CONFIG_MAP("configmaps", "v1", "", "Represents a ConfigMap in Kubernetes"),
    PERSISTENT_VOLUME("persistentvolumes", "v1", "", "Represents a PersistentVolume in Kubernetes"),
    PERSISTENT_VOLUME_CLAIM("persistentvolumeclaims", "v1", "", "Represents a PersistentVolumeClaim in Kubernetes"),
    ENDPOINT("endpoints", "v1", "", "Represents an Endpoint in Kubernetes"),
    SERVICE_ACCOUNT("serviceaccounts", "v1", "", "Represents a ServiceAccount in Kubernetes"),
    REPLICATION_CONTROLLER("replicationcontrollers", "v1", "", "Represents a ReplicationController in Kubernetes"),

    // Apps API Group (apps/v1)
    DEPLOYMENT("deployments", "apps/v1", "apps", "Represents a Deployment in Kubernetes"),
    STATEFUL_SET("statefulsets", "apps/v1", "apps", "Represents a StatefulSet in Kubernetes"),
    REPLICA_SET("replicasets", "apps/v1", "apps", "Represents a ReplicaSet in Kubernetes"),
    DAEMON_SET("daemonsets", "apps/v1", "apps", "Represents a DaemonSet in Kubernetes"),

    // RBAC API Group (rbac.authorization.k8s.io)
    NAMESPACE_ROLE("roles", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a Role in Kubernetes RBAC"),
    CLUSTER_ROLE("clusterroles", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a ClusterRole in Kubernetes RBAC"),
    ROLE_BINDING("rolebindings", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a RoleBinding in Kubernetes RBAC"),
    CLUSTER_ROLE_BINDING("clusterrolebindings", "rbac.authorization.k8s.io/v1", "rbac.authorization.k8s.io", "Represents a ClusterRoleBinding in Kubernetes RBAC"),

    // Networking API Group (networking.k8s.io)
    INGRESS("ingresses", "networking.k8s.io/v1", "networking.k8s.io", "Represents an Ingress resource in Kubernetes"),
    NETWORK_POLICY("networkpolicies", "networking.k8s.io/v1", "networking.k8s.io", "Represents a NetworkPolicy resource in Kubernetes"),

    // Storage API Group (storage.k8s.io)
    STORAGE_CLASS("storageclasses", "storage.k8s.io/v1", "storage.k8s.io", "Represents a StorageClass in Kubernetes"),
    VOLUME_ATTACHMENT("volumeattachments", "storage.k8s.io/v1", "storage.k8s.io", "Represents a VolumeAttachment in Kubernetes"),

    // Batch API Group (batch/v1)
    JOB("jobs", "batch/v1", "batch", "Represents a Job in Kubernetes"),
    CRON_JOB("cronjobs", "batch/v1", "batch", "Represents a CronJob in Kubernetes"),

    // Events API Group (events.k8s.io)
    EVENT("events", "events.k8s.io/v1", "events.k8s.io", "Represents an Event in Kubernetes"),

    // Additional Resource Types (commonly used)
    RESOURCEQUOTA("resourcequotas", "v1", "", "Represents a ResourceQuota in Kubernetes"),
    LIMITRANGE("limitranges", "v1", "", "Represents a LimitRange in Kubernetes"),
    HORIZONTALPODAUTOSCALER("horizontalpodautoscalers", "autoscaling/v1", "autoscaling", "Represents a HorizontalPodAutoscaler in Kubernetes"),

    // Custom Resource Definition (CRD) entry for the enum
    CUSTOM_RESOURCE_DEFINITION("customresourcedefinitions", "apiextensions.k8s.io/v1", "apiextensions.k8s.io", "Represents a CustomResourceDefinition (CRD) in Kubernetes, allowing for custom resource types");

    final String resourceName;
    final String apiVersion;
    final String apiGroup;
    final String description;

    K8ResourceType(String resourceName, String apiVersion, String apiGroup, String description) {
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

