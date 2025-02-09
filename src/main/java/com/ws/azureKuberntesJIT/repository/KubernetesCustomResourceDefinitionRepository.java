package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8CustomResourceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KubernetesCustomResourceDefinitionRepository extends JpaRepository<K8CustomResourceDefinition, Long> {
}
