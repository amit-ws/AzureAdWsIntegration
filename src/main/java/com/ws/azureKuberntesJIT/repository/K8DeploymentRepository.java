package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8Deployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8DeploymentRepository extends JpaRepository<K8Deployment, Long> {
    List<K8Deployment> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);

}
