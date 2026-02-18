package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
import com.ws.mcpAgenticAIMgmt.model.*;
import com.ws.mcpAgenticAIMgmt.repository.EnterprisePolicyRepository;
import com.ws.mcpAgenticAIMgmt.repository.EnterpriseRepository;
import com.ws.mcpAgenticAIMgmt.util.HelperUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class RegoPolicyGeneratorV2 {
    final EnterpriseRepository enterpriseRepository;
    final EnterprisePolicyRepository enterprisePolicyRepository;
    final OpaClientService opaClientService;

    @Autowired
    public RegoPolicyGeneratorV2(EnterpriseRepository enterpriseRepository, EnterprisePolicyRepository enterprisePolicyRepository, OpaClientService opaClientService) {
        this.enterpriseRepository = enterpriseRepository;
        this.enterprisePolicyRepository = enterprisePolicyRepository;
        this.opaClientService = opaClientService;
    }

    private Enterprise getByEnterpriseId(UUID enterpriseId) {
        return enterpriseRepository.findByEnterpriseId(enterpriseId)
                .orElseThrow(() -> new WsAgenticAIMgmtException("No Enterprise found with provided id"));
    }

    public Map<String, UUID> createAndSaveRego(EnterprisePolicy policy) {
        policy.setInUse(true);
        policy.getPolicyRules().forEach((policyRule -> {
            policyRule.setEnterprisePolicy(policy);
            policyRule.getConditions().forEach(policyRuleCondition -> policyRuleCondition.setPolicyRule(policyRule));
        }));
        UUID savedPolicyId = enterprisePolicyRepository.save(policy).getId();
        log.info("id: {}", policy.getEnterpriseId());

        Enterprise enterprise = getByEnterpriseId(policy.getEnterpriseId());
        enterprise.setCurrentPolicyId(savedPolicyId);
        enterpriseRepository.save(enterprise);

        String rego = generateRegoPolicy(policy, savedPolicyId);
        opaClientService.saveRegoInOpa(rego, savedPolicyId.toString());

//        log.info("Generated Rego Policy: {}", rego);
        return Map.of("policyId", savedPolicyId);
    }

    private String generateRegoPolicy(EnterprisePolicy policy, UUID policyId) {
        StringBuilder regoPolicy = new StringBuilder();

        regoPolicy.append("package ").append(HelperUtil.createPackageNameForOpaRego(policyId)).append(" \n\n");

        // New default allow rule: allow if no failed conditions
        regoPolicy.append("default allow := false\n\n");
        regoPolicy.append("allow if count(failed_conditions) == 0\n\n");

//        // Time validation helper rule for time conditions
//        regoPolicy.append(generateTimeValidationHelper(policy));

        if (policy.getPolicyRules() != null) {
            for (PolicyRule rule : policy.getPolicyRules()) {
                regoPolicy.append(generateFailedConditionsForRule(rule));
            }
        }

        return regoPolicy.toString();
    }

//    private String generateTimeValidationHelper(EnterprisePolicy policy) {
//        // If you want a helper for time, or you can inline per condition, for example:
//        // This method is optional and depends on how your time conditions are defined.
//        return ""; // Placeholder, can be implemented if needed.
//    }

    private String generateFailedConditionsForRule(PolicyRule rule) {
        StringBuilder sb = new StringBuilder();

        // Rule identifier check as failed condition
        sb.append(generateFailedCondition("input.ruleName != \"" + rule.getRuleName() + "\"",
                "invalid_rule_name"));

        PolicyTarget target = rule.getPolicyTarget();
        if (target != null) {
            if (target.getAgentId() != null && !target.getAgentId().isEmpty()) {
                sb.append(generateFailedCondition("input.target.agentId != \"" + target.getAgentId() + "\"",
                        "unauthorized_agent"));
            }
            if (target.getResourceType() != null && !target.getResourceType().isEmpty()) {
                sb.append(generateFailedCondition("input.target.resourceType != \"" + target.getResourceType() + "\"",
                        "invalid_resource_type"));

                if ("resource".equalsIgnoreCase(target.getResourceType())) {
                    if (target.getAction() != null && !target.getAction().isEmpty()) {
                        for (String action : target.getAction()) {
                            // Check that the action is in input.target.action, else fail
                            sb.append(generateFailedCondition("not \"" + action + "\" in input.target.action",
                                    "missing_action_" + action.replaceAll("\\W", "_").toLowerCase()));
                        }
                    }
                }
            }
            if (target.getResource() != null && !target.getResource().isEmpty()) {
                sb.append(generateFailedCondition("input.target.resource != \"" + target.getResource() + "\"",
                        "unauthorized_resource"));
            }
        }

        if (rule.getConditions() != null && !rule.getConditions().isEmpty()) {
            for (PolicyRuleCondition condition : rule.getConditions()) {
                sb.append(generateFailedConditionForCondition(condition));
            }
        }

        return sb.toString();
    }

    private String generateFailedCondition(String conditionCheck, String reason) {
        return "failed_conditions[\"" + reason + "\"] if {\n    " + conditionCheck + "\n}\n\n";
    }

    private String generateFailedConditionForCondition(PolicyRuleCondition condition) {
        switch (condition.getType()) {
            case "time":
                return generateTimeFailedCondition(condition);
            case "dataSensitivity":
                return generateDataSensitivityFailedCondition(condition);
            case "agentRiskScore":
                return generateAgentRiskScoreFailedCondition(condition);
            default:
                return ""; // Or throw exception, or add default generic failed condition
        }
    }

    private String generateTimeFailedCondition(PolicyRuleCondition condition) {
        StringBuilder sb = new StringBuilder();

        String timeZone = condition.getTimeZone();
        String operator = condition.getOperator();
        String ruleIdSuffix = UUID.randomUUID().toString().replace("-", "_").substring(0, 8);
        String reasonTimeZone = "unsupported_time_zone";
        String reasonTimeWindow = "request_outside_permitted_time";

        // Time zone mismatch reason
        sb.append("failed_conditions[\"").append(reasonTimeZone).append("\"] if {\n");
        sb.append("    input.context.timeZone != \"").append(timeZone).append("\"\n");
        sb.append("}\n\n");

        // Time window checks (with helper for 'between')
        if ("between".equalsIgnoreCase(operator)) {
            String helperName = "is_within_permitted_time_" + ruleIdSuffix;

            sb.append("failed_conditions[\"").append(reasonTimeWindow).append("\"] if {\n");
            sb.append("    input.context.timeZone == \"").append(timeZone).append("\"\n");
            sb.append("    not ").append(helperName).append("\n");
            sb.append("}\n\n");

            sb.append(helperName).append(" if {\n");
            sb.append("    input.context.currentTime >= \"").append(condition.getStartTime()).append("\"\n");
            sb.append("    input.context.currentTime <= \"").append(condition.getEndTime()).append("\"\n");
            sb.append("}\n\n");
        } else {
            sb.append("failed_conditions[\"").append(reasonTimeWindow).append("\"] if {\n");
            sb.append("    input.context.timeZone == \"").append(timeZone).append("\"\n");

            switch (operator) {
                case "before":
                    sb.append("    not input.context.currentTime < \"").append(condition.getValue()).append("\"\n");
                    break;
                case "after":
                    sb.append("    not input.context.currentTime > \"").append(condition.getValue()).append("\"\n");
                    break;
                case "equals":
                    sb.append("    input.context.currentTime != \"").append(condition.getValue()).append("\"\n");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported time operator: " + operator);
            }

            sb.append("}\n\n");
        }

        return sb.toString();
    }

    private String generateDataSensitivityFailedCondition(PolicyRuleCondition condition) {
        StringBuilder sb = new StringBuilder();
        String reason = "data_sensitivity_too_high";

        sb.append("failed_conditions[\"").append(reason).append("\"] if {\n");

        switch (condition.getOperator()) {
            case "equals":
                sb.append("    input.context.dataSensitivity != ").append(condition.getValue()).append("\n");
                break;
            case "greaterThan":
                sb.append("    input.context.dataSensitivity <= ").append(condition.getValue()).append("\n");
                break;
            case "lessThan":
                sb.append("    input.context.dataSensitivity >= ").append(condition.getValue()).append("\n");
                break;
            default:
                break;
        }

        sb.append("}\n\n");

        return sb.toString();
    }

    private String generateAgentRiskScoreFailedCondition(PolicyRuleCondition condition) {
        StringBuilder sb = new StringBuilder();
        String reason = "agent_risk_too_high";

        sb.append("failed_conditions[\"").append(reason).append("\"] if {\n");

        switch (condition.getOperator()) {
            case "equals":
                sb.append("    input.context.agentRiskScore != ").append(condition.getValue()).append("\n");
                break;
            case "greaterThan":
                sb.append("    input.context.agentRiskScore <= ").append(condition.getValue()).append("\n");
                break;
            case "lessThan":
                sb.append("    input.context.agentRiskScore >= ").append(condition.getValue()).append("\n");
                break;
            default:
                break;
        }

        sb.append("}\n\n");

        return sb.toString();
    }

}
