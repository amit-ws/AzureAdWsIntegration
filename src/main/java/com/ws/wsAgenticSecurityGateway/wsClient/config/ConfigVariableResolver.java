package com.ws.wsAgenticSecurityGateway.wsClient.config;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ConfigVariableResolver {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^:]+):([^}]+)\\}");

    private final Map<String, String> inputs;

    public ConfigVariableResolver(Map<String, String> inputs) {
        this.inputs = inputs;
    }

    public String resolve(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String type = matcher.group(1);
            String key = matcher.group(2);

            String resolvedValue = resolveVariable(type, key);

            if (resolvedValue != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(resolvedValue));
            } else {
 log.warn("⚠ Variable ${{}:{}} could not be resolved, keeping as-is", type, key);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private String resolveVariable(String type, String key) {
        switch (type.toLowerCase()) {
            case "env":
                return resolveEnvVariable(key);
            case "input":
                return resolveInputVariable(key);
            default:
 log.warn("⚠ Unknown variable type: {}", type);
                return null;
        }
    }

    private String resolveEnvVariable(String key) {
        String value = System.getenv(key);
        if (value != null) {
 log.debug("Resolved env variable: {} = {}", key, maskSensitive(key, value));
        } else {
 log.warn("⚠ Environment variable not found: {}", key);
        }
        return value;
    }

    private String resolveInputVariable(String key) {
        if (inputs == null) {
 log.warn("⚠ No inputs defined in config");
            return null;
        }

        String value = inputs.get(key);
        if (value != null) {
 log.debug("Resolved input variable: {} = {}", key, maskSensitive(key, value));
        } else {
 log.warn("⚠ Input variable not found: {}", key);
        }
        return value;
    }

    public Map<String, String> resolveMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }

        map.replaceAll((key, value) -> resolve(value));
        return map;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveObjectMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }

        map.replaceAll((key, value) -> {
            if (value instanceof String) {
                return resolve((String) value);
            } else if (value instanceof Map) {
                return resolveObjectMap((Map<String, Object>) value);
            }
            return value;
        });
        return map;
    }

    private String maskSensitive(String key, String value) {
        String lowerKey = key.toLowerCase();
        if (lowerKey.contains("token") ||
                lowerKey.contains("password") ||
                lowerKey.contains("secret") ||
                lowerKey.contains("key") ||
                lowerKey.contains("api")) {
            if (value != null && value.length() > 8) {
                return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
            }
            return "****";
        }
        return value;
    }
}
