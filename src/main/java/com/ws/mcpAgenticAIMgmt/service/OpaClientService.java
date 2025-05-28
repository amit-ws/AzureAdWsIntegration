package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OpaClientService {

    public Boolean saveRegoInOpa(String rego, String policyId) {
        String opaUrl = "http://localhost:8181/v1/policies/" + policyId;

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        HttpEntity<String> entity = new HttpEntity<>(rego, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.PUT,
                entity,
                String.class
        );

        return response.getStatusCode().is2xxSuccessful() ? Boolean.TRUE : Boolean.FALSE;
    }


    public String fetchPolicyUsingPolicyId(String policyId) {
        try {
            String opaUrl = "http://localhost:8181/v1/policies/" + policyId;
            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<Map> response = restTemplate.exchange(
                    opaUrl,
                    HttpMethod.GET,
                    null,
                    Map.class
            );

            Map body = response.getBody();
            if (ObjectUtils.isEmpty(body) || !body.containsKey("result")) {
                throw new WsAgenticAIMgmtException("No rego found with policy in OPA");
            }

            Map result = (Map) body.get("result");
            return (String) result.get("raw");

        } catch (Exception ex) {
            log.error("Error: {}", ex.getMessage());
            if (ex.getMessage().contains("400")) {
                throw new WsAgenticAIMgmtException("Invalid request sent to OPA");
            } else {
                throw new RuntimeException("Internal server error");
            }
        }
    }


    public Map<String, Object> evaluate(String inputOpaJson, String policyPackageName) {
        String opaUrl = String.format("http://localhost:8181/v1/data/%s", policyPackageName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(inputOpaJson, headers);
        RestTemplate restTemplate = new RestTemplate();

        ResponseEntity<Map> response = restTemplate.exchange(
                opaUrl,
                HttpMethod.POST,
                entity,
                Map.class
        );

        log.info("Response Status: {}", response.getStatusCode());
        log.info("Raw Response Body: {}", response.getBody());

        Map responseBody = response.getBody();

        boolean allow = false;
        List<String> failedConditions = Collections.emptyList();

        if (responseBody != null && responseBody.containsKey("result")) {
            Object resultObj = responseBody.get("result");
            if (resultObj instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) resultObj;
                Object allowObj = resultMap.get("allow");
                if (allowObj instanceof Boolean) {
                    allow = (Boolean) allowObj;
                }
                Object failedConditionsObj = resultMap.get("failed_conditions");
                if (failedConditionsObj instanceof Map) {
                    Map<String, Object> failedConditionsMap = (Map<String, Object>) failedConditionsObj;
                    if (!failedConditionsMap.isEmpty()) {
                        failedConditions = new ArrayList<>(failedConditionsMap.keySet());
                    }
                }
            }
        }

        return Map.of(
                "flag", allow,
                "failedConditions", failedConditions,
                "opaResponse", responseBody
        );
    }


}
