//package com.ws.mcpAccessMgmt.service;
//
//import com.ws.mcpAccessMgmt.entity.AccessRequest;
//import com.ws.mcpAccessMgmt.entity.Policy;
//import com.ws.mcpAccessMgmt.entity.PolicyCondition;
//import lombok.AllArgsConstructor;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import lombok.Setter;
//
//import java.time.LocalTime;
//import java.util.List;
//
//public class ContextualAuthorization {
//
//    public static boolean isAccessAllowed(AccessRequest request, Policy policy) {
//        return policy.getConditions().stream()
//                .allMatch(condition -> evaluateCondition(condition, request));
//    }
//
//    private static boolean evaluateCondition(PolicyCondition condition, AccessRequest request) {
//        switch (condition.getAttribute()) {
//            case "user.role":
//                return request.getUserRole().equals(condition.getValue());
//            case "resource.sensitivity":
//                return request.getResourceSensitivity().equals(condition.getValue());
//            case "time.hour":
//                int currentHour = request.getTime().getHour();
//                return currentHour >= Integer.parseInt(condition.getMin())
//                        && currentHour <= Integer.parseInt(condition.getMax());
//            case "risk_score":
//                return request.getRiskScore() < Double.parseDouble(condition.getValue());
//            default:
//                return false; // Fail-safe
//        }
//    }
//
//
//
////    // Data Models
////
////    @Data
////    @Setter
////    public static class AccessRequest {
////        private String userRole;          // e.g., "doctor", "ai_agent"
////        private String resourceSensitivity; // e.g., "PHI", "PUBLIC"
////        private LocalTime time;
////        private double riskScore;
////        // Getters
////    }
////
////    @Data
////    @AllArgsConstructor
////    @NoArgsConstructor
////    public static class Policy {
////        private List<Condition> conditions;
////        // Getters
////    }
////
////
////    @Data
////    @Setter
////    @AllArgsConstructor
////    public static class Condition {
////        private String attribute; // e.g., "user.role"
////        private String operator;  // e.g., "EQUALS", "RANGE"
////        private String value;     // e.g., "PHI", "9-17"
////        private String min;       // For RANGE operators
////        private String max;       // For RANGE operators
////    }
////
////    public static void main(String[] args) {
////        // Example Policy: Allow doctors to access PHI data between 9 AM–5 PM
////        Policy policy = new Policy(List.of(
////                new Condition("user.role", "EQUALS", "doctor", null, null),
////                new Condition("resource.sensitivity", "EQUALS", "PHI", null, null),
////                new Condition("time.hour", "RANGE", null, "9", "17")
////        ));
////
////        // Example Request: Doctor accessing PHI at 2 PM
////        AccessRequest request = new AccessRequest();
////        request.setUserRole("doctor");
////        request.setResourceSensitivity("PHI");
////        request.setTime(LocalTime.of(14, 0)); // 2 PM
////
////        System.out.println(isAccessAllowed(request, policy)); // Output: true
////    }
//}