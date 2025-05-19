package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EnterprisePolicyRepository extends JpaRepository<EnterprisePolicy, UUID> {

    List<EnterprisePolicy> findAllByEnterpriseIdOrderByCreatedAT(UUID entepriseId);

}
