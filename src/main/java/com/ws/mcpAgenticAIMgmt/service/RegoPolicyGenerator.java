package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
import com.ws.mcpAgenticAIMgmt.model.*;
import com.ws.mcpAgenticAIMgmt.repository.EnterprisePolicyRepository;
import com.ws.mcpAgenticAIMgmt.repository.EnterpriseRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class RegoPolicyGenerator {
    final EnterpriseRepository enterpriseRepository;
    final EnterprisePolicyRepository enterprisePolicyRepository;
    final OpaClientService opaClientService;

    @Autowired
    public RegoPolicyGenerator(EnterpriseRepository enterpriseRepository, EnterprisePolicyRepository enterprisePolicyRepository, OpaClientService opaClientService) {
        this.enterpriseRepository = enterpriseRepository;
        this.enterprisePolicyRepository = enterprisePolicyRepository;
        this.opaClientService = opaClientService;
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
        enterprise.setCurrentPolicyId(savedPolicyId.toString());
        enterpriseRepository.save(enterprise);

        String rego = generateRegoPolicy(policy, savedPolicyId);
        opaClientService.saveRefoInOpa(rego, savedPolicyId.toString());

        return Map.of("policyId", savedPolicyId);
    }

    public List<EnterprisePolicy> getEnterprisePolicyByEnterpriseId(String enterpriseId) {
        Enterprise enterprise = getByEnterpriseId(UUID.fromString(enterpriseId));
        List<EnterprisePolicy> policies = enterprisePolicyRepository.findAllByEnterpriseIdOrderByCreatedAT(UUID.fromString(enterpriseId));
        if (CollectionUtils.isEmpty(policies)) {
            throw new WsAgenticAIMgmtException("No Policies Found for the Enterprise: " + enterprise.getEnterpriseName());
        }
        return policies;
    }

    public Map<String, String> getRegoForEnterprisePolicy(String policyId) {
        getById(UUID.fromString(policyId));
        return Map.of("rego", opaClientService.fetchPolicyUsingPolicyId(policyId));
    }

    private Enterprise getByEnterpriseId(UUID enterpriseId) {
        return enterpriseRepository.findByEnterpriseId(enterpriseId).orElseThrow(() -> new WsAgenticAIMgmtException("No Enterprise found with provided id"));
    }

    private EnterprisePolicy getById(UUID id) {
        return enterprisePolicyRepository.findById(id).orElseThrow(() -> new WsAgenticAIMgmtException("No policy found with provided id: " + id));
    }

    private String generateRegoPolicy(EnterprisePolicy policy, UUID policyId) {
        StringBuilder regoPolicy = new StringBuilder();

        regoPolicy.append("package policy_").append(policyId.toString().replace("-", "_")).append(" \n\n");
        regoPolicy.append("default allow := false\n\n");

        if (policy.getPolicyRules() != null) {
            for (PolicyRule rule : policy.getPolicyRules()) {
                regoPolicy.append(generateRuleRego(rule));
            }
        }

        return regoPolicy.toString();
    }


    private String generateRuleRego(PolicyRule rule) {
        StringBuilder ruleRego = new StringBuilder();

        String ruleType = rule.getEffect() != null && rule.getEffect().equalsIgnoreCase("deny") ? "deny if " : "allow if ";
        ruleRego.append(ruleType).append(" {\n");

        ruleRego.append("    input.ruleName == \"").append(rule.getRuleName()).append("\"\n");

        PolicyTarget target = rule.getPolicyTarget();
        if (target != null) {
            if (target.getAgentId() != null && !target.getAgentId().isEmpty()) {
                ruleRego.append("    input.target.agentId == \"").append(target.getAgentId()).append("\"\n");
            }
            if (target.getResourceType() != null && !target.getResourceType().isEmpty()) {
                ruleRego.append("    input.target.resourceType == \"").append(target.getResourceType()).append("\"\n");

                if (target.getResourceType().equalsIgnoreCase("resource")) {
                    if (target.getAction() != null && !target.getAction().isEmpty()) {
                        for (String action : target.getAction()) {
                            ruleRego.append("    \"").append(action).append("\" in input.target.action\n");
                        }
                    }
                }
            }
            if (target.getResource() != null && !target.getResource().isEmpty()) {
                ruleRego.append("    input.target.resource == \"").append(target.getResource()).append("\"\n");
            }
        }

        if (rule.getConditions() != null && !rule.getConditions().isEmpty()) {
            for (PolicyRuleCondition condition : rule.getConditions()) {
                ruleRego.append(generateConditionRego(condition));
            }
        }

        ruleRego.append("}\n\n");
        return ruleRego.toString();
    }

    private String generateConditionRego(PolicyRuleCondition condition) {
        StringBuilder conditionRego = new StringBuilder();

        switch (condition.getType()) {
            case "time":
                conditionRego.append(generateTimeConditionRego(condition));
                break;
            case "dataSensitivity":
                conditionRego.append(generateDataSensitivityConditionRego(condition));
                break;
            case "agentRiskScore":
                conditionRego.append(generateAgentRiskScoreConditionRego(condition));
                break;
            default:
                throw new IllegalArgumentException("Unsupported condition type: " + condition.getType());
        }

        return conditionRego.toString();
    }

    private String generateTimeConditionRego(PolicyRuleCondition condition) {
        StringBuilder timeConditionRego = new StringBuilder();
        timeConditionRego.append("    input.timeZone == \"").append(condition.getTimeZone()).append("\"\n");

        switch (condition.getOperator()) {
            case "between":
                timeConditionRego.append("    input.currentTime >= \"").append(condition.getStartTime()).append("\"\n");
                timeConditionRego.append("    input.currentTime <= \"").append(condition.getEndTime()).append("\"\n");
                break;
            case "before":
                timeConditionRego.append("    input.currentTime < \"").append(condition.getValue()).append("\"\n");
                break;
            case "after":
                timeConditionRego.append("    input.currentTime > \"").append(condition.getValue()).append("\"\n");
                break;
            case "equals":
                timeConditionRego.append("    input.currentTime == \"").append(condition.getValue()).append("\"\n");
                break;
            default:
                throw new IllegalArgumentException("Unsupported time operator: " + condition.getOperator());
        }

        return timeConditionRego.toString();
    }

    private String generateDataSensitivityConditionRego(PolicyRuleCondition condition) {
        StringBuilder dataSensitivityRego = new StringBuilder();

        switch (condition.getOperator()) {
            case "equals":
                dataSensitivityRego.append("    input.dataSensitivity == ").append(condition.getValue()).append("\n");
                break;
            case "greaterThan":
                dataSensitivityRego.append("    input.dataSensitivity > ").append(condition.getValue()).append("\n");
                break;
            case "lessThan":
                dataSensitivityRego.append("    input.dataSensitivity < ").append(condition.getValue()).append("\n");
                break;
            default:
                throw new IllegalArgumentException("Unsupported data sensitivity operator: " + condition.getOperator());
        }

        return dataSensitivityRego.toString();
    }

    private String generateAgentRiskScoreConditionRego(PolicyRuleCondition condition) {
        StringBuilder agentRiskScoreRego = new StringBuilder();

        switch (condition.getOperator()) {
            case "equals":
                agentRiskScoreRego.append("    input.agentRiskScore == ").append(condition.getValue()).append("\n");
                break;
            case "greaterThan":
                agentRiskScoreRego.append("    input.agentRiskScore > ").append(condition.getValue()).append("\n");
                break;
            case "lessThan":
                agentRiskScoreRego.append("    input.agentRiskScore < ").append(condition.getValue()).append("\n");
                break;
            default:
                throw new IllegalArgumentException("Unsupported agent risk score operator: " + condition.getOperator());
        }

        return agentRiskScoreRego.toString();
    }
}
