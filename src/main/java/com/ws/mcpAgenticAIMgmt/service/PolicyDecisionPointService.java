package com.ws.mcpAgenticAIMgmt.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.mcpAgenticAIMgmt.constant.PdpDecision;
import com.ws.mcpAgenticAIMgmt.constant.PdpProcessedReason;
import com.ws.mcpAgenticAIMgmt.dto.OpaEvaluationInput;
import com.ws.mcpAgenticAIMgmt.dto.PepRequest;
import com.ws.mcpAgenticAIMgmt.dto.PdpResponse;
import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import com.ws.mcpAgenticAIMgmt.model.PdpAuditLogEntry;
import com.ws.mcpAgenticAIMgmt.model.PolicyRule;
import com.ws.mcpAgenticAIMgmt.repository.EnterprisePolicyRepository;
import com.ws.mcpAgenticAIMgmt.repository.EnterpriseRepository;
import com.ws.mcpAgenticAIMgmt.util.HelperUtil;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class PolicyDecisionPointService {
    final PdpAuditLogService pdpAuditLogService;
    final EnterpriseRepository enterpriseRepository;
    final EnterprisePolicyRepository enterprisePolicyRepository;
    final OpaClientService opaClientService;

    @Autowired
    public PolicyDecisionPointService(PdpAuditLogService pdpAuditLogService, EnterpriseRepository enterpriseRepository, EnterprisePolicyRepository enterprisePolicyRepository, OpaClientService opaClientService) {
        this.pdpAuditLogService = pdpAuditLogService;
        this.enterpriseRepository = enterpriseRepository;
        this.enterprisePolicyRepository = enterprisePolicyRepository;
        this.opaClientService = opaClientService;
    }

    @Transactional
    public PdpResponse processAuthorizationRequest(PepRequest pepRequest) {
        String requestId = pepRequest.getRequestId().trim();

        String enterpriseId = enterpriseRepository.findByEnterpriseName(pepRequest.getEnterpriseName().trim()).get().getEnterpriseId().toString();
        pepRequest.setEnterpriseId(enterpriseId);

        // 0. Save the Audit Log for the PDP request
        PdpAuditLogEntry logEntry = pdpAuditLogService.createAndSavePdpAuditLog(pepRequest);

        // 1. Check for the inUse Policy for the provided enterprise name from PMS
        Optional<EnterprisePolicy> foundPolicyOpt = enterprisePolicyRepository.findEnterprisePolicyUsingEnterpriseName(pepRequest.getEnterpriseName());
        if (foundPolicyOpt.isEmpty()) {
            pdpAuditLogService.updatePdpAuditLogFields(logEntry, PdpDecision.DECLINED, PdpProcessedReason.POLICY_NOT_FOUND_FOR_ENTERPRISE, null);
            return createPdpResponse(false, PdpProcessedReason.POLICY_NOT_FOUND_FOR_ENTERPRISE.name(), requestId, null);
        }
        EnterprisePolicy foundPolicy = foundPolicyOpt.get();
        logEntry.setPolicyIdUsed(foundPolicy.getId().toString());

        // 2. Check if there is any RULE(s) exists for the provided AI Agent
        String foundRuleName = null;
        String foundRuleEffect = null;
        for (PolicyRule policyRule : foundPolicy.getPolicyRules()) {
            if (pepRequest.getSubject().getAgentId().equalsIgnoreCase(
                    policyRule.getPolicyTarget().getAgentId())) {
                foundRuleName = policyRule.getRuleName();
                foundRuleEffect = policyRule.getEffect();
                break;
            }
        }

        if (StringUtils.isEmpty(foundRuleName)) {
            pdpAuditLogService.updatePdpAuditLogFields(logEntry, PdpDecision.DECLINED, PdpProcessedReason.POLICY_RULE_NOT_FOUND_FOR_AGENT, null);
            return createPdpResponse(false, PdpProcessedReason.POLICY_RULE_NOT_FOUND_FOR_AGENT.name(), requestId, null);
        }

        logEntry.setMatchedRuleName(foundRuleName);
        logEntry.setMatchedRuleEffect(foundRuleEffect);

        // 3. Prepare the request for the OPA using the provided PDP request
        String opaInput = createOpaInputPayload(pepRequest, foundRuleName);
        logEntry.setOpaInputSent(opaInput);

        // 4. Call OPA client to evaluate the request
        String policyPackageName = HelperUtil.createPackageNameForOpaRego(foundPolicy.getId());

        logEntry.setOpaRequestedAt(LocalDateTime.now());
        Map<String, Object> opaResponse = opaClientService.evaluate(opaInput, policyPackageName);
        logEntry.setOpaResponseAt(LocalDateTime.now());

        Object rawResponse = opaResponse.get("opaResponse");
        boolean decision = (boolean) (opaResponse.get("flag"));
        List<String> opaFailedReasons = (List<String>) opaResponse.get("failedConditions");
        logEntry.setOpaDecision(decision);
        logEntry.setOpaRawResponse(rawResponse != null ? rawResponse.toString() : null);

        PdpDecision pdpDecision = decision ? PdpDecision.ALLOWED : PdpDecision.DECLINED;
        PdpProcessedReason reason = decision
                ? PdpProcessedReason.POLICY_CONDITIONS_SATISFIED
                : PdpProcessedReason.POLICY_CONDITIONS_NOT_SATISFIED;

        pdpAuditLogService.updatePdpAuditLogFields(logEntry, pdpDecision, reason, opaFailedReasons);
        return createPdpResponse(decision, reason.name(), requestId, opaFailedReasons);
    }


    private PdpResponse createPdpResponse(boolean decision, String message, String requestId, List<String> failedReasons) {
        return PdpResponse.builder()
                .decision(decision)
                .message(message)
                .requestId(requestId)
                .failedReasons(failedReasons)
                .build();
    }

    private String createOpaInputPayload(PepRequest pepRequest, String ruleName) {
        OpaEvaluationInput.Input opaInput = OpaEvaluationInput.Input.builder()
                .ruleName(ruleName)
                .target(OpaEvaluationInput.Input.Target.builder()
                        .agentId(pepRequest.getSubject().getAgentId())
                        .resource(pepRequest.getResource().getResourceName())
                        .resourceType(ObjectUtils.isNotEmpty(pepRequest.getAction()) ? "resource" : "tool")
                        .actions(ObjectUtils.isNotEmpty(pepRequest.getAction()) ? pepRequest.getAction().getActions() : null)
                        .build())
                .context(OpaEvaluationInput.Input.ContextualData.builder()
                        .timeZone(pepRequest.getEnvironmentContext().getTimeZone())
                        .currentTime(pepRequest.getEnvironmentContext().getCurrentTime())
                        .dataSensitivity(pepRequest.getEnvironmentContext().getDataSensitivityLevel())
                        .agentRiskScore(pepRequest.getEnvironmentContext().getAgentRiskScore())
                        .build())
                .build();

        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("input", opaInput);

        ObjectMapper mapper = new ObjectMapper();
        try {
            String json = mapper.writeValueAsString(requestPayload);  // Convert the wrapped payload to JSON
            log.info("OPA JSON: {}", json);
            return json;
        } catch (JsonProcessingException e) {
            log.error("Error in converting the opaInput to JSON: {}", e.getMessage());
            throw new RuntimeException("Internal Server Error");
        }
    }

}

