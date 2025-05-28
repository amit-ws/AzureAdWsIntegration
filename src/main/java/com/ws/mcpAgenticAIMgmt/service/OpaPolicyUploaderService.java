package com.ws.mcpAgenticAIMgmt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@Slf4j
public class OpaPolicyUploaderService {

    public String uploadPolicy() {
        String policyId = UUID.randomUUID().toString();
        log.info("policyId: {}", policyId);
        String opaUrl = "http://localhost:8181/v1/policies/" + policyId;
        String policy = """
                
                package authorization_package
                
                default allow := false
                
                # Allow only if no failed conditions exist
                allow if count(failed_conditions) == 0
                
                # Collect reasons as a set of strings with explicit 'if' conditions
                failed_conditions[reason] if {
                	input.ruleName != "rule-1"
                	reason := "Invalid rule name"
                }
                
                failed_conditions[reason] if {
                	input.target.agentId != "hr-agent-v1-123"
                	reason := "Unauthorized agent"
                }
                
                failed_conditions[reason] if {
                	input.target.resourceType != "tool"
                	reason := "Invalid resource type"
                }
                
                failed_conditions[reason] if {
                	input.target.resource != "get_employee_data()"
                	reason := "Unauthorized resource"
                }
                
                failed_conditions[reason] if {
                	input.context.timeZone != "UTC"
                	reason := "Unsupported time zone"
                }
                
                failed_conditions[reason] if {
                	not time_valid
                	reason := "Request outside permitted time"
                }
                
                failed_conditions[reason] if {
                	input.context.dataSensitivity >= 3
                	reason := "Data sensitivity too high"
                }
                
                failed_conditions[reason] if {
                	input.context.agentRiskScore >= 4
                	reason := "Agent risk too high"
                }
                
                # Time validation helper
                time_valid if {
                	input.context.currentTime >= "10:00"
                	input.context.currentTime <= "17:00"
                }
                
                
                
                """;

        log.info("policy: {}", policy);

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        HttpEntity<String> entity = new HttpEntity<>(policy, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.PUT,
                entity,
                String.class
        );

        System.out.println("Status code: " + response.getStatusCode());
        System.out.println("Response from OPA: " + response.getBody());

        return response.getBody();
    }


    public String fetchPolicy(String policyId) {
        String opaUrl = "http://localhost:8181/v1/policies/" + policyId;

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.GET,
                null,
                Map.class
        );

        Map body = response.getBody();

        if (body != null && body.containsKey("result")) {
            Map result = (Map) body.get("result");
            return (String) result.get("raw");
        }

        return null;
    }


    public List<String> listPolicyIds() {
        String opaUrl = "http://localhost:8181/v1/policies";

        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<Map> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.GET,
                null,
                Map.class
        );

        Map body = response.getBody();
        List<String> ids = new ArrayList<>();

        if (body != null && body.containsKey("result")) {
            List<Map> result = (List<Map>) body.get("result");
            for (Map policy : result) {
                ids.add((String) policy.get("id"));
            }
        }

        return ids;
    }


    public boolean evaluate(String user, String opaPackageName) {
        String opaUrl = String.format("http://localhost:8181/v1/data/%s/allow", opaPackageName);

        String inputJson = String.format("""
                {
                  "input": {
                    "user": "%s"
                  }
                }
                """, user);

        log.info("inputJson: {}", inputJson);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(inputJson, headers);
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<Map> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.POST,
                entity,
                Map.class
        );

        Map responseBody = response.getBody();
        log.info("responseBody: {}", responseBody);
        Object result = responseBody != null ? responseBody.get("result") : null;
        return Boolean.TRUE.equals(result);
    }


    public Map<String, Object> evaluateV2(String opaPackageName) {
        //        String opaUrl = String.format("http://localhost:8181/v1/data/%s/allow", opaPackageName); // <----- Gives only the result as true/false
        String opaUrl = String.format("http://localhost:8181/v1/data/%s", opaPackageName);

        String inputJson = """
                {
                    "input": {
                        "ruleName": "rule-1",
                        "target": {
                            "agentId": "hr-agent-v1-123",
                            "resourceType": "tool",
                            "resource": "get_employee_data()"
                        },
                        "context": {
                            "timeZone": "UTC",
                            "currentTime": "19:00",
                            "dataSensitivity": 11,
                            "agentRiskScore": 2
                        }
                    }
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(inputJson, headers);
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<Map> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.POST,
                entity,
                Map.class
        );

        Map responseBody = response.getBody();
        log.info("Full OPA Response: {}", responseBody);

        boolean allowed = false;
        Set<String> failedConditions = Collections.emptySet();

        if (responseBody != null && responseBody.containsKey("result")) {
            Map<String, Object> result = (Map<String, Object>) responseBody.get("result");
            if (result.containsKey("allow")) {
                allowed = (Boolean) result.get("allow");
            }
            if (result.containsKey("failed_conditions")) {
                Map<String, Boolean> failedConditionsMap = (Map<String, Boolean>) result.get("failed_conditions");
                failedConditions = new HashSet<>(failedConditionsMap.keySet());
            }
        }

       return Map.of("allow", allowed, "failed_conditions", failedConditions);
    }


}
