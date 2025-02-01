package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.PublishedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface PublishedResourcesRepository extends JpaRepository<PublishedResource, Integer> {
    @Modifying
    void deleteByResourceIdAndWsTenantName(String  resourceId, String wsTenantName);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantMame);

}
