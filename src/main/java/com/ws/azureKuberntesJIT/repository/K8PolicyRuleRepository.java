package com.ws.azureKuberntesJIT.repository;

import com.ws.azureAdIntegration.constants.CloudProviderType;
import com.ws.azureKuberntesJIT.enttity.K8RolePolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface K8PolicyRuleRepository extends JpaRepository<K8RolePolicyRule, Long> {
    List<K8RolePolicyRule> findByRoleUIDAndWsTenantNameAndCloudProviderType(String roleUID, String wsTenantName, CloudProviderType cloudProviderType);
}
