package com.ws.wsAgenticSecurityGateway.pdp.repository;

import com.ws.wsAgenticSecurityGateway.pdp.entity.GatewayCustomAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link GatewayCustomAttributeEntity}.
 */
@Repository
public interface GatewayCustomAttributeRepository extends JpaRepository<GatewayCustomAttributeEntity, UUID> {

    /** All enabled attributes, used for runtime resolution. */
    List<GatewayCustomAttributeEntity> findByEnabledTrueOrderByAttributeNameAsc();

    /** Lookup by attribute name (unique). */
    Optional<GatewayCustomAttributeEntity> findByAttributeName(String attributeName);

    /** Check if an attribute name already exists. */
    boolean existsByAttributeName(String attributeName);

    /** Filter by value source. */
    List<GatewayCustomAttributeEntity> findByValueSource(String valueSource);

    /** Count enabled attributes. */
    long countByEnabledTrue();
}
