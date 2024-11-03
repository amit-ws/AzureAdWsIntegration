package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.BackendApplicationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackendApplicationLogRepository extends JpaRepository<BackendApplicationLog, Integer> {
}
