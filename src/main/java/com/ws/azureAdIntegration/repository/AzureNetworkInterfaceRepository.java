package com.ws.azureAdIntegration.repository;

import com.ws.azureResourcesIntegration.entities.AzureNetworkInterface;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AzureNetworkInterfaceRepository extends JpaRepository<AzureNetworkInterface, Long> {
}
