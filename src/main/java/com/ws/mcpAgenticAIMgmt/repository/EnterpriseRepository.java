package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.Enterprise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EnterpriseRepository extends JpaRepository<Enterprise, UUID> {
    Optional<Enterprise> findByContactEmail(String email);
    Optional<Enterprise> findByEnterpriseId(UUID enterpriseId);
}
