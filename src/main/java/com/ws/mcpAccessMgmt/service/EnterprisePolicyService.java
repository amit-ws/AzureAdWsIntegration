package com.ws.mcpAccessMgmt.service;

import com.ws.mcpAccessMgmt.model.EnterprisePolicyRequest;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class EnterprisePolicyService {

    public String convertToRego(EnterprisePolicyRequest policyRequest) {
        StringBuilder regoCode = new StringBuilder();

        regoCode.append("package policy.ai_access\n\n");
        regoCode.append("# Zero Trust policy \n");
        regoCode.append("default allow := false\n\n");
        regoCode.append("# Policy-1 \n");
        regoCode.append("allow if {\n");

        Boolean authenticationRequired = policyRequest.isAuthenticationRequired();
        if (authenticationRequired != null && authenticationRequired) {
            regoCode.append("    # Check if user is authenticated\n");
            regoCode.append("    input.ai.id != \"\"\n\n");
        }

        List<String> allowedAiAgents = policyRequest.getAllowedAiAgents();
        if (allowedAiAgents != null && !allowedAiAgents.isEmpty()) {
            regoCode.append("    # Check if ai agent is under allowed list\n");
            if (allowedAiAgents.size() == 1) {
                regoCode.append("    \"");
                regoCode.append(allowedAiAgents.get(0));
                regoCode.append("\" in input.ai.agents\n\n");
            } else {
                regoCode.append("    (\n");
                for (int i = 0; i < allowedAiAgents.size(); i++) {
                    regoCode.append("        \"");
                    regoCode.append(allowedAiAgents.get(i));
                    regoCode.append("\" in input.ai.agents");
                    if (i < allowedAiAgents.size() - 1) {
                        regoCode.append(" or\n");
                    } else {
                        regoCode.append("\n    )\n\n");
                    }
                }
            }
        }

        String allowedAction = policyRequest.getAction();
        if (allowedAction != null && !allowedAction.isEmpty()) {
            regoCode.append("    # Validate requested action is permitted\n");
            regoCode.append("    input.action == \"");
            regoCode.append(allowedAction);
            regoCode.append("\"\n\n");
        }

        String allowedResource = policyRequest.getResource();
        if (allowedResource != null && !allowedResource.isEmpty()) {
            regoCode.append("    # Verify requested resource\n");
            regoCode.append("    input.resource == \"");
            regoCode.append(allowedResource);
            regoCode.append("\"\n");
        }

        regoCode.append("}\n");

        return regoCode.toString();
    }
}