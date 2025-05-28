package com.ws.mcpAgenticAIMgmt.repository;

import com.ws.mcpAgenticAIMgmt.model.PdpAuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PdpAuditEntryRepository extends JpaRepository<PdpAuditLogEntry, UUID> {
    List<PdpAuditLogEntry> findAllByEnterpriseIdOrderByRequestedAtDesc(String enterpriseId);
}


//https://chat.nonbios.ai/68dd1dbc-a41c-474f-b96d-d4afd732d160/chat