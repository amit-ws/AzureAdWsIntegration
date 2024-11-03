package com.ws.azureAdIntegration.util;

import java.util.Optional;

public class GenericUtil {
    public static void ensureNotNull(Object object, String message) {
        Optional.ofNullable(object)
                .orElseThrow(() -> new IllegalArgumentException(message));
    }
}