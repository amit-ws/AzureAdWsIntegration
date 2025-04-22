package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.EnterprisePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EnterprisePolicyRepository extends JpaRepository<EnterprisePolicy, Long> {
}
