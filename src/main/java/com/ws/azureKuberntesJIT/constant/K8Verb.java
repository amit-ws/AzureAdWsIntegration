package com.ws.azureKuberntesJIT.constant;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
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

    final String verb;

    K8Verb(String verb) {
        this.verb = verb;
    }

    public static List<String> getAllVerb() {
        return Arrays.stream(K8Verb.values())
                .map(K8Verb::getVerb)
                .collect(Collectors.toList());
    }

}
