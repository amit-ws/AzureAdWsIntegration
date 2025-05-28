package com.ws.mcpAgenticAIMgmt.model;

import com.ws.mcpAgenticAIMgmt.constant.PdpDecision;
import com.ws.mcpAgenticAIMgmt.constant.PdpProcessedReason;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "pdp_audit_log", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
public class PdpAuditLogEntry {
    @Id
    @GeneratedValue
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false)
    String requestId;  // Unique Id sent by the PEP
    @Column(nullable = false)
    LocalDateTime requestedAt;
    LocalDateTime requestCompletedAt;
    @Column(nullable = false)
    String enterpriseName;
    @Column(nullable = false)
    String enterpriseId;

    @Lob
    @Column(nullable = false)
    String pepRequestPayload;   // String value of the JSON

    @Enumerated(EnumType.STRING)
    PdpDecision finalPdpDecision;
    @Enumerated(EnumType.STRING)
    PdpProcessedReason reason;

    String policyIdUsed;
    String matchedRuleName;
    String matchedRuleEffect;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "opa_failed_reasons_audit_log",
            schema = "ws_agentic_ai_iam",
            joinColumns = @JoinColumn(name = "pdp_audit_log_id")
    )
    @Column(name = "opa_failed_reason")
    List<String> opaFailedReasons;

    @Lob
    String opaInputSent;        // String value of the JSON
    String opaRawResponse;
    boolean opaDecision;
    LocalDateTime opaRequestedAt;
    LocalDateTime opaResponseAt;
}
