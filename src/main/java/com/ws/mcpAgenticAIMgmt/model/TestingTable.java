//package com.ws.mcpAgenticAIMgmt.model;
//
//import jakarta.persistence.*;
//import lombok.AllArgsConstructor;
//import lombok.Builder;
//import lombok.NoArgsConstructor;
//
//import java.util.List;
//import java.util.UUID;
//
//@Entity
//@Builder
//@AllArgsConstructor
//@NoArgsConstructor
//@Table(name = "testing_table", schema = "ws_agentic_ai_iam")
//public class TestingTable {
//    @Id
//    @GeneratedValue
//    @Builder.Default
//    private UUID id = UUID.randomUUID();
//
//    @ElementCollection
//    @CollectionTable(name = "kube_policy_rule_verbs", joinColumns = @JoinColumn(name = "policyy_rule_id"))
//    @Column(name = "verb")
//    List<String> verbs;
//}
