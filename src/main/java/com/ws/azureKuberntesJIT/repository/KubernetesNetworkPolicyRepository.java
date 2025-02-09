package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8NetworkPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KubernetesNetworkPolicyRepository extends JpaRepository<K8NetworkPolicy, Long> {
}
