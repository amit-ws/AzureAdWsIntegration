package com.ws.wsAgenticSecurityGateway.pdp.repository;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatewayCustomAttributeRepository extends JpaRepository<GatewayCustomAttributeEntity, UUID> {

    List<GatewayCustomAttributeEntity> findByEnabledTrueOrderByAttributeNameAsc();

    Optional<GatewayCustomAttributeEntity> findByAttributeName(String attributeName);

    boolean existsByAttributeName(String attributeName);

    List<GatewayCustomAttributeEntity> findByValueSource(String valueSource);

    long countByEnabledTrue();

    List<GatewayCustomAttributeEntity> findByEnabledTrueAndWsTenantNameOrderByAttributeNameAsc(String wsTenantName);

    Optional<GatewayCustomAttributeEntity> findByAttributeNameAndWsTenantName(String attributeName, String wsTenantName);

    boolean existsByAttributeNameAndWsTenantName(String attributeName, String wsTenantName);

    List<GatewayCustomAttributeEntity> findByValueSourceAndWsTenantName(String valueSource, String wsTenantName);

    long countByEnabledTrueAndWsTenantName(String wsTenantName);

    List<GatewayCustomAttributeEntity> findAllByWsTenantName(String wsTenantName);
}
