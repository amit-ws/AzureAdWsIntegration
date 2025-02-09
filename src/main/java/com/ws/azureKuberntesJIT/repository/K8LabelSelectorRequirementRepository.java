package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8LabelSelectorRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface K8LabelSelectorRequirementRepository extends JpaRepository<K8LabelSelectorRequirement, Long> {
}
