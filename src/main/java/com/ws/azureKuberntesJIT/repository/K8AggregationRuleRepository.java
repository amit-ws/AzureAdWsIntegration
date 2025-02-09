package com.ws.azureKuberntesJIT.repository;

import com.ws.azureKuberntesJIT.enttity.K8AggregationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface K8AggregationRuleRepository extends JpaRepository<K8AggregationRule, Long> {
}
