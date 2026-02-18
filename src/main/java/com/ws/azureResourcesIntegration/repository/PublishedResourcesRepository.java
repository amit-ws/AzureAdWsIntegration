package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.constant.PublishResourceType;
import com.ws.azureResourcesIntegration.entities.PublishedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;

public interface PublishedResourcesRepository extends JpaRepository<PublishedResource, Integer> {
    @Modifying
    void deleteByResourceIdAndResourceAccountIdAndWsTenantName(String resourceId, String resourceAccountId, String wsTenantName);

    @Modifying
    @Query("DELETE FROM PublishedResource pr WHERE pr.wsTenantName = :wsTenantMame AND (:resourceAccountIds IS NULL OR pr.resourceAccountId IN :resourceAccountIds) ")
    void deleteAllByWsTenantName(String wsTenantMame, Collection<String> resourceAccountIds);


    Optional<PublishedResource> findByResourceIdAndResourceTypeAndResourceAccountIdAndWsTenantName(String resourceId, PublishResourceType resourceType,
                                                                                                   String resourceAccountId, String wsTenantName);

}
