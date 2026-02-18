package com.ws.mcpAgenticAIMgmt.util;

import java.util.UUID;

public class HelperUtil {

    public static String createPackageNameForOpaRego(UUID policyId) {
        return "policy_" + policyId.toString().replace("-", "_");
    }


    public static String sanitizeRuleName(String ruleName) {
        return "rule_" + ruleName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public static String sanitizeConditionName(String conditionId) {
        return "condition_" + conditionId.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
