package com.ws.wsAgenticSecurityGateway.sts.repository;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsRotationPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayStsRotationPolicyRepository
        extends JpaRepository<GatewayStsRotationPolicyEntity, UUID> {

    Optional<GatewayStsRotationPolicyEntity> findByWsTenantName(String wsTenantName);

    /** Tenants opted into auto-rotation — driven by the scheduled sweep. */
    List<GatewayStsRotationPolicyEntity> findByAutoRotateTrue();
}
