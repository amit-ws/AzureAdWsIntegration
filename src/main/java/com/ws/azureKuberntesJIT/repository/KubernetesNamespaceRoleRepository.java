package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8NamespaceRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KubernetesNamespaceRoleRepository extends JpaRepository<K8NamespaceRole, Long> {
}
