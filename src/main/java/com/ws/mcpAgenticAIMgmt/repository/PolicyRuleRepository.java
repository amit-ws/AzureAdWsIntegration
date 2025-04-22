package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyRuleRepository extends JpaRepository<PolicyRule, Long> {
}
