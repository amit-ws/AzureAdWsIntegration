package com.ws.azureResourcesIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureUserConfigure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AzureUserConfigureRepository extends JpaRepository<AzureUserConfigure, Integer> {
    Optional<AzureUserConfigure> findByEmailAndWsTenantName(String userEmail, String wsTenantName);
    @Query("SELECT COUNT(a) > 0 FROM AzureUserConfigure a WHERE a.azureId = :azureId OR a.email = :email")
    boolean existsByAzureIdOrEmail(@Param("azureId") String azureId, @Param("email") String email);

    @Modifying
    void deleteAllByWsTenantName(String wsTenantName);
}
