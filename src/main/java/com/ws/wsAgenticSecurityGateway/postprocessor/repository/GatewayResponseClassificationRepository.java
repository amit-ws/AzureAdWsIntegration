package com.ws.wsAgenticSecurityGateway.postprocessor.repository;

import com.ws.wsAgenticSecurityGateway.postprocessor.entity.GatewayResponseClassificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store for the post-processor's egress classifications. The classifier writes here (async) as responses flow;
 * the sensitivity overlay, per-tool/agent fingerprints, and the CISO Track-2 views read from here.
 */
@Repository
public interface GatewayResponseClassificationRepository
        extends JpaRepository<GatewayResponseClassificationEntity, UUID> {

    /** Idempotency guard — one classification per source response event. */
    Optional<GatewayResponseClassificationEntity> findBySourceEventId(UUID sourceEventId);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndCorrelationId(String wsTenantName, String correlationId);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndProducer(String wsTenantName, String producer);

    List<GatewayResponseClassificationEntity> findByWsTenantNameAndSensitivity(String wsTenantName, String sensitivity);
}
