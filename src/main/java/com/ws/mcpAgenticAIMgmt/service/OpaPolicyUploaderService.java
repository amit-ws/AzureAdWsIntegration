package com.ws.mcpAgenticAIMgmt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class OpaPolicyUploaderService {

    public String uploadPolicy() {
        String policyId = UUID.randomUUID().toString();
        log.info("policyId: {}", policyId);
        String opaUrl = "http://localhost:8181/v1/policies/" + policyId;
        String policy = """
                package authPackage

                default allow = false

                allow if {
                  input.user == "admin"
                }
                """;

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

    public boolean evaluate(String user) {
        String opaUrl = "http://localhost:8181/v1/data/authz/allow";

        String inputJson = String.format("""
                {
                  "input": {
                    "user": "%s"
                  }
                }
                """, user);

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
}
