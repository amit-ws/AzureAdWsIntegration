package com.ws.wsAgenticSecurityGateway.pdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyEvaluationRequest {

    private String agentName;
    private String agentVersion;
    private String agentApprovalStatus;
    private String agentSessionId;

    private String agentClientId;
    private String jwtSubject;
    private List<String> agentRoles;
    private List<String> realmRoles;
    private List<String> clientRoles;
    private List<String> agentGroups;
    private String userIdentity;
    private String tokenType;
    private Map<String, Object> jwtCustomClaims;

    /** Delegation lineage (root human/NHI -> agent actor), in the act_chain claim form. */
    private List<Map<String, Object>> actChain;

    private String action;

    private String resourceName;
    private String serverName;
    private String originalName;
    private String resourceType;

    private Map<String, Object> arguments;
    private String correlationId;
    private String sourceIp;

    private Map<String, Object> customAttributes;
}
