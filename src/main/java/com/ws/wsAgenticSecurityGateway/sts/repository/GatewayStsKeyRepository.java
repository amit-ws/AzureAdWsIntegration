package com.ws.wsAgenticSecurityGateway.sts.repository;

import com.ws.wsAgenticSecurityGateway.sts.entity.GatewayStsKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayStsKeyRepository extends JpaRepository<GatewayStsKeyEntity, UUID> {

    Optional<GatewayStsKeyEntity> findFirstByWsTenantNameAndStatus(String wsTenantName, String status);

    List<GatewayStsKeyEntity> findByWsTenantNameAndStatusIn(String wsTenantName, Collection<String> statuses);

    List<GatewayStsKeyEntity> findByWsTenantNameOrderByCreatedAtDesc(String wsTenantName);
}
