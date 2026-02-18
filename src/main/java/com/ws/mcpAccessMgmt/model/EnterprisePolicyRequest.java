package com.ws.mcpAccessMgmt.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class EnterprisePolicyRequest {
    String action;
    String resource;
    boolean authenticationRequired;
    List<String> allowedAiAgents;
}
