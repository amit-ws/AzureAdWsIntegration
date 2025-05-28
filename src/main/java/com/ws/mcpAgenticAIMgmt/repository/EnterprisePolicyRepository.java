package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnterprisePolicyRepository extends JpaRepository<EnterprisePolicy, UUID> {

    List<EnterprisePolicy> findAllByEnterpriseIdOrderByCreatedAT(UUID entepriseId);

    @Query("SELECT ep FROM Enterprise e INNER JOIN EnterprisePolicy ep ON e.currentPolicyId = ep.id WHERE e.enterpriseName = :enterpriseName")
    Optional<EnterprisePolicy> findEnterprisePolicyUsingEnterpriseName(String enterpriseName);

}
