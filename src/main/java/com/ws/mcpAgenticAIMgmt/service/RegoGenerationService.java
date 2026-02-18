//package com.ws.mcpAgenticAIMgmt.service;
//
//import com.ws.mcpAgenticAIMgmt.model.*;
//import org.springframework.stereotype.Service;
//
//import java.util.stream.Collectors;
//
//@Service
//public class RegoGenerationService {
//
//    public String convertPolicyToRego(EnterprisePolicy enterprisePolicy) {
//        StringBuilder regoBuilder = new StringBuilder();
//
//        regoBuilder.append("# Policy Name: ").append(enterprisePolicy.getPolicyName()).append("\n");
//        regoBuilder.append("# Description: ").append(enterprisePolicy.getDescription()).append("\n");
//        regoBuilder.append("# Version: ").append(enterprisePolicy.getVersion()).append("\n\n");
//        regoBuilder.append("package com.example.authz\n\n");
//
//        for (PolicyRule policyRule : enterprisePolicy.getPolicyRules()) {
//            regoBuilder.append("# Rule ID: ").append(policyRule.getRuleId()).append("\n");
//            regoBuilder.append("# Description: ").append(policyRule.getDescription()).append("\n");
//
//            String ruleName = policyRule.getRuleId().replace("-", "_");
//            regoBuilder.append("default ").append(ruleName).append(" := false\n\n");
//            regoBuilder.append(ruleName).append(" if {\n");
//
//            if (policyRule.getPolicyTarget() != null) {
//                if (policyRule.getPolicyTarget().getAgentId() != null && !policyRule.getPolicyTarget().getAgentId().equals("*")) {
//                    regoBuilder.append("  input.agentId == \"").append(policyRule.getPolicyTarget().getAgentId()).append("\"\n");
//                }
//                if (policyRule.getPolicyTarget().getResource() != null && !policyRule.getPolicyTarget().getResource().equals("*")) {
//                    regoBuilder.append("  input.resource == \"").append(policyRule.getPolicyTarget().getResource()).append("\"\n");
//                }
//                if (policyRule.getPolicyTarget().getAction() != null && !policyRule.getPolicyTarget().getAction().equals("*")) {
//                    regoBuilder.append("  input.action == \"").append(policyRule.getPolicyTarget().getAction()).append("\"\n");
//                }
//            }
//
//            if (policyRule.getConditions() != null && !policyRule.getConditions().isEmpty()) {
//                for (PolicyRuleCondition condition : policyRule.getConditions()) {
//                    regoBuilder.append("  ");
//                    switch (condition.getType()) {
//                        case "time":
//                            if (condition.getOperator().equals("between")) {
//                                regoBuilder.append("time.now_in(\"").append(condition.getTimeZone()).append("\") >= time.parse_rfc3339(\"").append(java.time.LocalTime.parse(condition.getStartTime()).atDate(java.time.LocalDate.now()).toString()).append("Z\")\n");
//                                regoBuilder.append("  time.now_in(\"").append(condition.getTimeZone()).append("\") <= time.parse_rfc3339(\"").append(java.time.LocalTime.parse(condition.getEndTime()).atDate(java.time.LocalDate.now()).toString()).append("Z\")\n");
//                            } else if (condition.getOperator().equals("notBetween")) {
//                                regoBuilder.append("not (\ntime.now_in(\"").append(condition.getTimeZone()).append("\") >= time.parse_rfc3339(\"").append(java.time.LocalTime.parse(condition.getStartTime()).atDate(java.time.LocalDate.now()).toString()).append("Z\")\n");
//                                regoBuilder.append("  time.now_in(\"").append(condition.getTimeZone()).append("\") <= time.parse_rfc3339(\"").append(java.time.LocalTime.parse(condition.getEndTime()).atDate(java.time.LocalDate.now()).toString()).append("Z\")\n)\n");
//                            }
//                            break;
//                        case "location":
//                            if (condition.getOperator().equals("equals")) {
//                                regoBuilder.append("input.location == \"").append(condition.getValue()).append("\"\n");
//                            } else if (condition.getOperator().equals("notEquals")) {
//                                regoBuilder.append("input.location != \"").append(condition.getValue()).append("\"\n");
//                            }
//                            break;
//                        case "dataSensitivity":
//                            if (condition.getOperator().equals("equals")) {
//                                regoBuilder.append("input.data.sensitivity == \"").append(condition.getValue()).append("\"\n");
//                            }
//                            break;
//                        case "intent":
//                            if (condition.getOperator().equals("equals")) {
//                                regoBuilder.append("input.intent == \"").append(condition.getValue()).append("\"\n");
//                            }
//                            break;
//                        case "requestPayload":
//                            if (condition.getOperator().equals("fieldLessThanOrEqual") && condition.getField() != null && condition.getNumericValue() != null) {
//                                regoBuilder.append("input.payload.").append(condition.getField()).append(" <= ").append(condition.getNumericValue()).append("\n");
//                            } else if (condition.getOperator().equals("fieldGreaterThanOrEqual") && condition.getField() != null && condition.getNumericValue() != null) {
//                                regoBuilder.append("input.payload.").append(condition.getField()).append(" >= ").append(condition.getNumericValue()).append("\n");
//                            }
//                            break;
//                        case "securityLevel":
//                            if (condition.getOperator().equals("greaterThanOrEqual") && condition.getNumericValue() != null) {
//                                regoBuilder.append("input.securityLevel >= ").append(condition.getNumericValue()).append("\n");
//                            }
//                            break;
//                        case "attribute":
//                            if (condition.getOperator().equals("equals") && condition.getName() != null && condition.getValue() != null) {
//                                regoBuilder.append("input.").append(condition.getName()).append(" == \"").append(condition.getValue()).append("\"\n");
//                            }
//                            break;
//                        case "protocol":
//                            if (condition.getOperator().equals("in") && condition.getValues() != null && !condition.getValues().isEmpty()) {
//                                String values = condition.getValues().stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
//                                regoBuilder.append("input.protocol in [").append(values).append("]\n");
//                            } else if (condition.getOperator().equals("notIn") && condition.getValues() != null && !condition.getValues().isEmpty()) {
//                                String values = condition.getValues().stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
//                                regoBuilder.append("not (input.protocol in [").append(values).append("])\n");
//                            }
//                            break;
//                        case "resource":
//                            if (condition.getOperator().equals("matches") && condition.getPattern() != null) {
//                                regoBuilder.append("re_match(\"").append(condition.getPattern()).append("\", input.resource)\n");
//                            }
//                            break;
//                        case "ipAddress":
//                            if (condition.getOperator().equals("inRange") && condition.getStartIP() != null && condition.getEndIP() != null) {
//                                regoBuilder.append("net.cidr_contains(\"").append(condition.getStartIP()).append("/32\", input.ipAddress) or net.cidr_contains(\"").append(condition.getEndIP()).append("/32\", input.ipAddress) # Basic IP range - consider more robust logic\n");
//                            }
//                            break;
//                        case "dayOfWeek":
//                            if (condition.getOperator().equals("in") && condition.getValues() != null && !condition.getValues().isEmpty()) {
//                                String days = condition.getValues().stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
//                                regoBuilder.append("time.weekday(time.now()) in [").append(days).append("]\n");
//                            } else if (condition.getOperator().equals("notIn") && condition.getValues() != null && !condition.getValues().isEmpty()) {
//                                String days = condition.getValues().stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
//                                regoBuilder.append("not (time.weekday(time.now()) in [").append(days).append("])\n");
//                            }
//                            break;
//                        default:
//                            System.err.println("Unsupported condition type: " + condition.getType());
//                    }
//                }
//            }
//
//            regoBuilder.append("  input.effect == \"").append(policyRule.getEffect()).append("\"\n");
//            regoBuilder.append("}\n\n");
//        }
//
//        return regoBuilder.toString();
//    }
//}