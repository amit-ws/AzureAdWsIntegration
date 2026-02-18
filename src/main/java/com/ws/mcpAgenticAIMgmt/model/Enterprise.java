package com.ws.mcpAgenticAIMgmt.model;

import com.ws.mcpAgenticAIMgmt.constant.EnterpriseStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.GenericGenerator;

import java.util.Date;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "enterprise", schema = "ws_agentic_ai_iam")
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Enterprise {
    @Id
    @GeneratedValue
    UUID enterpriseId;

    @Column(nullable = false)
    String enterpriseName;

    String logoUrl;
    String description;

    @Column(nullable = false, unique = true)
    String contactEmail;
    String industry;
    String region;
    @Enumerated(EnumType.STRING)
    EnterpriseStatus status;
    boolean ssoEnabled;
    UUID currentPolicyId;
    String createdBy;

    @Builder.Default
    Date createAt = new Date();
    Date updatedAt;
}
