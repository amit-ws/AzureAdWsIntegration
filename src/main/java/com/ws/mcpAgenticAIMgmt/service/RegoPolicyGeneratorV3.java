//package com.ws.mcpAgenticAIMgmt.service;
//
//import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
//import com.ws.mcpAgenticAIMgmt.model.*;
//import com.ws.mcpAgenticAIMgmt.repository.EnterprisePolicyRepository;
//import com.ws.mcpAgenticAIMgmt.repository.EnterpriseRepository;
//import com.ws.mcpAgenticAIMgmt.util.HelperUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.*;
//
//@Slf4j
//@Service
//public class RegoPolicyGeneratorV2 {
//    final EnterpriseRepository enterpriseRepository;
//    final EnterprisePolicyRepository enterprisePolicyRepository;
//    final OpaClientService opaClientService;
//
//    @Autowired
//    public RegoPolicyGeneratorV2(EnterpriseRepository enterpriseRepository, EnterprisePolicyRepository enterprisePolicyRepository, OpaClientService opaClientService) {
//        this.enterpriseRepository = enterpriseRepository;
//        this.enterprisePolicyRepository = enterprisePolicyRepository;
//        this.opaClientService = opaClientService;
//    }
//
//    public Map<String, UUID> createAndSaveRego(EnterprisePolicy policy) {
//        policy.setInUse(true);
//        policy.getPolicyRules().forEach((policyRule -> {
//            policyRule.setEnterprisePolicy(policy);
//            policyRule.getConditions().forEach(policyRuleCondition -> policyRuleCondition.setPolicyRule(policyRule));
//        }));
//        UUID savedPolicyId = enterprisePolicyRepository.save(policy).getId();
//        log.info("id: {}", policy.getEnterpriseId());
//
//        Enterprise enterprise = getByEnterpriseId(policy.getEnterpriseId());
//        enterprise.setCurrentPolicyId(savedPolicyId);
//        enterpriseRepository.save(enterprise);
//
//        String rego = generateRegoPolicy(policy, savedPolicyId);
//        opaClientService.saveRefoInOpa(rego, savedPolicyId.toString());
//
//        return Map.of("policyId", savedPolicyId);
//    }
//
//    public List<EnterprisePolicy> getEnterprisePolicyByEnterpriseId(String enterpriseId) {
//        Enterprise enterprise = getByEnterpriseId(UUID.fromString(enterpriseId));
//        List<EnterprisePolicy> policies = enterprisePolicyRepository.findAllByEnterpriseIdOrderByCreatedAT(UUID.fromString(enterpriseId));
//        if (CollectionUtils.isEmpty(policies)) {
//            throw new WsAgenticAIMgmtException("No Policies Found for the Enterprise: " + enterprise.getEnterpriseName());
//        }
//        return policies;
//    }
//
//    public Map<String, String> getRegoForEnterprisePolicy(String policyId) {
//        getById(UUID.fromString(policyId));
//        return Map.of("rego", opaClientService.fetchPolicyUsingPolicyId(policyId));
//    }
//
//    private Enterprise getByEnterpriseId(UUID enterpriseId) {
//        return enterpriseRepository.findByEnterpriseId(enterpriseId).orElseThrow(() -> new WsAgenticAIMgmtException("No Enterprise found with provided id"));
//    }
//
//    private EnterprisePolicy getById(UUID id) {
//        return enterprisePolicyRepository.findById(id).orElseThrow(() -> new WsAgenticAIMgmtException("No policy found with provided id: " + id));
//    }
//
//
//    /**
//     * Generates the OPA Rego policy string, including detailed failed conditions.
//     * This method now constructs the Rego to return a set of failed conditions
//     * when the 'allow' rule evaluates to false.
//     *
//     * @param policy   The EnterprisePolicy object containing rules and conditions.
//     * @param policyId The UUID of the policy, used for package naming.
//     * @return The complete Rego policy string.
//     */
//    private String generateRegoPolicy(EnterprisePolicy policy, UUID policyId) {
//        StringBuilder regoPolicy = new StringBuilder();
//        StringBuilder failedConditionsRego = new StringBuilder();
//        StringBuilder helperRulesRego = new StringBuilder();
//        Set<String> generatedHelperRules = new HashSet<>(); // To ensure helpers like time_valid are generated only once
//
//        // 1. Package and default allow
//        regoPolicy.append("package ").append(HelperUtil.createPackageNameForOpaRego(policyId)).append(" \n\n");
//        regoPolicy.append("default allow := false\n\n");
//
//        // 2. Main allow rule based on failed_conditions count
//        regoPolicy.append("# Allow only if no failed conditions exist\n");
//        regoPolicy.append("allow if count(failed_conditions) == 0\n\n");
//
//        // 3. Collect failed conditions
//        failedConditionsRego.append("# Collect reasons as a set of strings with explicit 'if' conditions\n");
//
//        if (policy.getPolicyRules() != null) {
//            for (PolicyRule rule : policy.getPolicyRules()) {
//                // Rule Name condition
//                failedConditionsRego.append("failed_conditions[reason] if {\n");
//                failedConditionsRego.append("    input.ruleName != \"").append(rule.getRuleName()).append("\"\n");
//                failedConditionsRego.append("    reason := \"Invalid rule name: ").append(rule.getRuleName()).append("\"\n");
//                failedConditionsRego.append("}\n\n");
//
//                // Policy Target conditions
//                PolicyTarget target = rule.getPolicyTarget();
//                if (target != null) {
//                    generateFailedConditionsForTarget(target, failedConditionsRego);
//                }
//
//                // Policy Rule Conditions
//                if (rule.getConditions() != null && !rule.getConditions().isEmpty()) {
//                    for (PolicyRuleCondition condition : rule.getConditions()) {
//                        generateFailedConditionsForCondition(condition, failedConditionsRego, helperRulesRego, generatedHelperRules);
//                    }
//                }
//            }
//        }
//
//        // Append generated failed conditions and helper rules
//        regoPolicy.append(failedConditionsRego);
//        regoPolicy.append(helperRulesRego);
//
//        return regoPolicy.toString();
//    }
//
//    /**
//     * Generates Rego rules for failed target conditions.
//     *
//     * @param target               The PolicyTarget object.
//     * @param failedConditionsRego StringBuilder to append failed conditions rules.
//     */
//    private void generateFailedConditionsForTarget(PolicyTarget target, StringBuilder failedConditionsRego) {
//        if (target.getAgentId() != null && !target.getAgentId().isEmpty()) {
//            failedConditionsRego.append("failed_conditions[reason] if {\n");
//            failedConditionsRego.append("    input.target.agentId != \"").append(target.getAgentId()).append("\"\n");
//            failedConditionsRego.append("    reason := \"Unauthorized agent: ").append(target.getAgentId()).append("\"\n");
//            failedConditionsRego.append("}\n\n");
//        }
//        if (target.getResourceType() != null && !target.getResourceType().isEmpty()) {
//            failedConditionsRego.append("failed_conditions[reason] if {\n");
//            failedConditionsRego.append("    input.target.resourceType != \"").append(target.getResourceType()).append("\"\n");
//            failedConditionsRego.append("    reason := \"Invalid resource type: ").append(target.getResourceType()).append("\"\n");
//            failedConditionsRego.append("}\n\n");
//
//            if (target.getResourceType().equalsIgnoreCase("resource")) {
//                if (target.getAction() != null && !target.getAction().isEmpty()) {
//                    for (String action : target.getAction()) {
//                        failedConditionsRego.append("failed_conditions[reason] if {\n");
//                        failedConditionsRego.append("    not \"").append(action).append("\" in input.target.action\n");
//                        failedConditionsRego.append("    reason := \"Unauthorized action: ").append(action).append("\"\n");
//                        failedConditionsRego.append("}\n\n");
//                    }
//                }
//            }
//        }
//        if (target.getResource() != null && !target.getResource().isEmpty()) {
//            failedConditionsRego.append("failed_conditions[reason] if {\n");
//            failedConditionsRego.append("    input.target.resource != \"").append(target.getResource()).append("\"\n");
//            failedConditionsRego.append("    reason := \"Unauthorized resource: ").append(target.getResource()).append("\"\n");
//            failedConditionsRego.append("}\n\n");
//        }
//    }
//
//    /**
//     * Generates Rego rules for failed policy rule conditions (time, dataSensitivity, agentRiskScore).
//     *
//     * @param condition            The PolicyRuleCondition object.
//     * @param failedConditionsRego StringBuilder to append failed conditions rules.
//     * @param helperRulesRego      StringBuilder to append helper rules (e.g., time_valid).
//     * @param generatedHelperRules Set to track already generated helper rule names.
//     */
//    private void generateFailedConditionsForCondition(PolicyRuleCondition condition,
//                                                      StringBuilder failedConditionsRego,
//                                                      StringBuilder helperRulesRego,
//                                                      Set<String> generatedHelperRules) {
//        switch (condition.getType()) {
//            case "time":
//                generateTimeFailedConditionRego(condition, failedConditionsRego, helperRulesRego, generatedHelperRules);
//                break;
//            case "dataSensitivity":
//                generateDataSensitivityFailedConditionRego(condition, failedConditionsRego);
//                break;
//            case "agentRiskScore":
//                generateAgentRiskScoreFailedConditionRego(condition, failedConditionsRego);
//                break;
//            default:
//                throw new IllegalArgumentException("Unsupported condition type for failed conditions: " + condition.getType());
//        }
//    }
//
//    /**
//     * Generates Rego for failed time conditions and the time_valid helper.
//     *
//     * @param condition            The PolicyRuleCondition object for time.
//     * @param failedConditionsRego StringBuilder to append failed conditions rules.
//     * @param helperRulesRego      StringBuilder to append helper rules.
//     * @param generatedHelperRules Set to track already generated helper rule names.
//     */
//    private void generateTimeFailedConditionRego(PolicyRuleCondition condition,
//                                                 StringBuilder failedConditionsRego,
//                                                 StringBuilder helperRulesRego,
//                                                 Set<String> generatedHelperRules) {
//
//        // Generate time_valid helper rule if not already generated
//        if (!generatedHelperRules.contains("time_valid")) {
//            helperRulesRego.append("# Time validation helper\n");
//            helperRulesRego.append("time_valid if {\n");
//            helperRulesRego.append("    input.context.timeZone == \"").append(condition.getTimeZone()).append("\"\n");
//            switch (condition.getOperator()) {
//                case "between":
//                    helperRulesRego.append("    input.context.currentTime >= \"").append(condition.getStartTime()).append("\"\n");
//                    helperRulesRego.append("    input.context.currentTime <= \"").append(condition.getEndTime()).append("\"\n");
//                    break;
//                case "before":
//                    helperRulesRego.append("    input.context.currentTime < \"").append(condition.getValue()).append("\"\n");
//                    break;
//                case "after":
//                    helperRulesRego.append("    input.context.currentTime > \"").append(condition.getValue()).append("\"\n");
//                    break;
//                case "equals":
//                    helperRulesRego.append("    input.context.currentTime == \"").append(condition.getValue()).append("\"\n");
//                    break;
//                default:
//                    throw new IllegalArgumentException("Unsupported time operator: " + condition.getOperator());
//            }
//            helperRulesRego.append("}\n\n");
//            generatedHelperRules.add("time_valid");
//        }
//
//        // Generate failed_conditions rule for time
//        failedConditionsRego.append("failed_conditions[reason] if {\n");
//        failedConditionsRego.append("    not time_valid\n");
//        failedConditionsRego.append("    reason := \"Request outside permitted time (").append(condition.getStartTime()).append("-").append(condition.getEndTime()).append(" in ").append(condition.getTimeZone()).append(")\"\n");
//        failedConditionsRego.append("}\n\n");
//    }
//
//    /**
//     * Generates Rego for failed data sensitivity conditions.
//     *
//     * @param condition            The PolicyRuleCondition object for data sensitivity.
//     * @param failedConditionsRego StringBuilder to append failed conditions rules.
//     */
//    private void generateDataSensitivityFailedConditionRego(PolicyRuleCondition condition, StringBuilder failedConditionsRego) {
//        failedConditionsRego.append("failed_conditions[reason] if {\n");
//        String reason = "";
//        switch (condition.getOperator()) {
//            case "equals":
//                failedConditionsRego.append("    input.context.dataSensitivity != ").append(condition.getValue()).append("\n");
//                reason = "Data sensitivity not equal to " + condition.getValue();
//                break;
//            case "greaterThan":
//                failedConditionsRego.append("    input.context.dataSensitivity <= ").append(condition.getValue()).append("\n");
//                reason = "Data sensitivity not greater than " + condition.getValue();
//                break;
//            case "lessThan":
//                failedConditionsRego.append("    input.context.dataSensitivity >= ").append(condition.getValue()).append("\n");
//                reason = "Data sensitivity not less than " + condition.getValue();
//                break;
//            default:
//                throw new IllegalArgumentException("Unsupported data sensitivity operator: " + condition.getOperator());
//        }
//        failedConditionsRego.append("    reason := \"").append(reason).append("\"\n");
//        failedConditionsRego.append("}\n\n");
//    }
//
//    /**
//     * Generates Rego for failed agent risk score conditions.
//     *
//     * @param condition            The PolicyRuleCondition object for agent risk score.
//     * @param failedConditionsRego StringBuilder to append failed conditions rules.
//     */
//    private void generateAgentRiskScoreFailedConditionRego(PolicyRuleCondition condition, StringBuilder failedConditionsRego) {
//        failedConditionsRego.append("failed_conditions[reason] if {\n");
//        String reason = "";
//        switch (condition.getOperator()) {
//            case "equals":
//                failedConditionsRego.append("    input.context.agentRiskScore != ").append(condition.getValue()).append("\n");
//                reason = "Agent risk score not equal to " + condition.getValue();
//                break;
//            case "greaterThan":
//                failedConditionsRego.append("    input.context.agentRiskScore <= ").append(condition.getValue()).append("\n");
//                reason = "Agent risk score not greater than " + condition.getValue();
//                break;
//            case "lessThan":
//                failedConditionsRego.append("    input.context.agentRiskScore >= ").append(condition.getValue()).append("\n");
//                reason = "Agent risk score not less than " + condition.getValue();
//                break;
//            default:
//                throw new IllegalArgumentException("Unsupported agent risk score operator: " + condition.getOperator());
//        }
//        failedConditionsRego.append("    reason := \"").append(reason).append("\"\n");
//        failedConditionsRego.append("}\n\n");
//    }
//}