package com.ws.azureKuberntesJIT.constant;

import lombok.Getter;

@Getter
public enum K8Verb {
    // Basic CRUD Operations
    CREATE("create"),
    GET("get"),
    LIST("list"),
    UPDATE("update"),
    PATCH("patch"),
    DELETE("delete"),
    DELETE_COLLECTION("deletecollection"),

    // Cluster-specific operations
    WATCH("watch"),

    // Namespace-specific operations
    EXEC("exec"),
    PORT_FORWARD("portforward"),

    // Custom or advanced operations
    CONNECT("connect"),
    CLUSTER_INFO("clusterinfo"),

    // Non-standard operations
    APPLY("apply"),

    // Status and Metadata operations
    STATUS("status"),
    PATCH_STATUS("patchstatus"),

    PROXY("proxy"),
    ESCALATE("escalate"),
    USE("use"),
    APPROVE("approve"),
    SIGN("sign"),
    IMPERSONATE("impersonate");

    private final String verb;

    K8Verb(String verb) {
        this.verb = verb;
    }
}
