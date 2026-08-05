package com.ws.wsAgenticSecurityGateway.protocol.a2a.capability.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A registered downstream A2A agent — the persisted backing of the {@code SelfDescribeAgentSource}. Only the
 * agent's name and base URL are stored; its skills are (re)derived from the live Agent Card on ingest and on
 * startup reconcile, so the capability registry always reflects the agent's current card without a stale
 * per-skill copy.
 *
 * <p>Table auto-created by Hibernate {@code ddl-auto: update} in schema {@code ws_agentic_security};
 * {@code ws_tenant_name} is stamped by the default tenant entity listener.
 */
@Entity
@Table(name = "gateway_a2a_agent", schema = "ws_agentic_security",
        uniqueConstraints = @UniqueConstraint(columnNames = {"ws_tenant_name", "name"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class A2aAgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ws_tenant_name", nullable = false)
    private String wsTenantName;

    /** The gateway-facing name for this downstream agent (namespaces its skills). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** The agent's base URL — its Agent Card is at {@code <baseUrl>/.well-known/agent-card.json}. */
    @Column(name = "base_url", nullable = false, columnDefinition = "text")
    private String baseUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
