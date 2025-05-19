//package com.ws.mcpAgenticAIMgmt.service;
//
//import com.ws.mcpAgenticAIMgmt.dto.*;
//import org.springframework.stereotype.Service;
//
//@Service
//public class RegoGenerator {
//
//    public String generateRegoPolicy(Policy policy) {
//        StringBuilder rego = new StringBuilder();
//        rego.append("package agent.access\n\n");
//        rego.append("default allow = false\n\n");
//
//        for (Rule rule : policy.rules) {
//            String ruleName = "allow_" + rule.ruleId.replaceAll("-", "_");
//
//            rego.append("allow {\n");
//
//            // Target match
//            if (rule.target != null) {
//                rego.append("  input.agentId == \"" + rule.target.agentId + "\"\n");
//                rego.append("  input.resource == \"" + rule.target.resource + "\"\n");
//                rego.append("  input.action == \"" + rule.target.action + "\"\n");
//            }
//
//            // Conditions
//            if (rule.conditions != null) {
//                for (Condition condition : rule.conditions) {
//                    switch (condition.type) {
//                        case "attribute":
//                            rego.append("  input.context." + condition.name + " == \"" + condition.value + "\"\n");
//                            break;
//                        case "protocol":
//                            rego.append("  input.context.protocol == \"" + condition.values.get(0) + "\""); // simplifying
//                            if (condition.values.size() > 1) {
//                                for (int i = 1; i < condition.values.size(); i++) {
//                                    rego.append(" or input.context.protocol == \"" + condition.values.get(i) + "\"");
//                                }
//                                rego.append("\n");
//                            } else {
//                                rego.append("\n");
//                            }
//                            break;
//                        case "time":
//                            if ("notBetween".equals(condition.operator)) {
//                                rego.append("  not (input.context.time >= \"" + condition.startTime + "\" && input.context.time <= \"" + condition.endTime + "\")\n");
//                            }
//                            break;
//                        case "dayOfWeek":
//                            if ("notIn".equals(condition.operator)) {
//                                rego.append("  not input.context.dayOfWeek in {");
//                                rego.append(String.join(", ", condition.values.stream().map(v -> "\"" + v + "\"").toList()));
//                                rego.append("}\n");
//                            }
//                            break;
//                        default:
//                            // Handle other condition types as needed
//                            throw new RuntimeException("Invalid type!");
//                    }
//                }
//            }
//
//            // Effect
//            if ("deny".equalsIgnoreCase(rule.effect)) {
//                rego.append("  false  # deny effect, so don't allow\n");
//            }
//
//            rego.append("}\n\n");
//        }
//
//        return rego.toString();
//    }
//}
