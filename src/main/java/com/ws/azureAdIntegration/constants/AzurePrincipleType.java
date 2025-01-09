package com.ws.azureAdIntegration.constants;

import lombok.Getter;

@Getter
public enum AzurePrincipleType {
    USER("User"),
    GROUP("Group"),
    SERVICE_PRINCIPAL("ServicePrincipal"),
    DEVICE("Device");

    final String value;

    AzurePrincipleType(String value) {
        this.value = value;
    }
}
