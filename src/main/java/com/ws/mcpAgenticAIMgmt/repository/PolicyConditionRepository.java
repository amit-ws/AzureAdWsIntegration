package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.PolicyCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyConditionRepository extends JpaRepository<PolicyCondition, Long> {
}
