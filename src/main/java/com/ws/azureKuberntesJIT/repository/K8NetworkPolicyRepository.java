package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8NetworkPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8NetworkPolicyRepository extends JpaRepository<K8NetworkPolicy, Long> {
    List<K8NetworkPolicy> findAllByWsTenantNameAndCloudProviderType(String wsTenantName, CloudProviderType cloudProviderType);
}
