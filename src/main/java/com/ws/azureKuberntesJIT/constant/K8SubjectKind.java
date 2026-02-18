package com.ws.azureKuberntesJIT.constant;


import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
public enum K8SubjectKind {
    USER("User"),
    GROUP("Group"),
    SERVICE_ACCOUNT("ServiceAccount");

    final String kind;

    K8SubjectKind(String kind) {
        this.kind = kind;
    }
}
