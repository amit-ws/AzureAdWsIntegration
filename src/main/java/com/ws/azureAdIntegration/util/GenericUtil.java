package com.ws.azureAdIntegration.util;

import java.util.Optional;
import java.util.function.Supplier;

public class GenericUtil {
    public static void ensureNotNull(Object object, String message) {
        Optional.ofNullable(object)
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    public static <T> T getOrNull(Supplier<T> supplier) {
        try {
            return supplier != null ? supplier.get() : null;
        } catch (NullPointerException e) {
            return null;
        }
    }
}