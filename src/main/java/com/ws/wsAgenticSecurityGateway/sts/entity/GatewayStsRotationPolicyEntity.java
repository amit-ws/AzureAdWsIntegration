package com.ws.wsAgenticSecurityGateway.sts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-tenant STS key auto-rotation preference: whether to auto-rotate, and how often ({@code intervalDays}).
 * The scheduled sweep rotates a tenant's ACTIVE signing key once it is older than {@code intervalDays}.
 *
 * <p>Table auto-created by Hibernate {@code ddl-auto: update} in schema {@code ws_agentic_security}.
 */
@Entity
@Table(name = "gateway_sts_rotation_policy", schema = "ws_agentic_security")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayStsRotationPolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false, unique = true)
    private String wsTenantName;

    /** When true, the scheduled sweep rotates this tenant's key once it exceeds {@link #intervalDays}. */
    @Column(name = "auto_rotate", nullable = false)
    @Builder.Default
    private boolean autoRotate = false;

    /** Rotate the ACTIVE key once it is older than this many days (min 1). */
    @Column(name = "interval_days", nullable = false)
    @Builder.Default
    private int intervalDays = 90;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
