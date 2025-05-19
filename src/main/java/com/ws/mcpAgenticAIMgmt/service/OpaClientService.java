package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class OpaClientService {

    public Boolean saveRefoInOpa(String rego, String policyId) {
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


}
