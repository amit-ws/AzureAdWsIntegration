package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.constant.PdpDecision;
import com.ws.mcpAgenticAIMgmt.constant.PdpProcessedReason;
import com.ws.mcpAgenticAIMgmt.dto.PepRequest;
import com.ws.mcpAgenticAIMgmt.model.PdpAuditLogEntry;
import com.ws.mcpAgenticAIMgmt.repository.PdpAuditEntryRepository;
import com.ws.mcpAgenticAIMgmt.util.JsonConverter;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PdpAuditLogService {
    final PdpAuditEntryRepository pdpAuditEntryRepository;

    @Autowired
    public PdpAuditLogService(PdpAuditEntryRepository pdpAuditEntryRepository) {
        this.pdpAuditEntryRepository = pdpAuditEntryRepository;
    }

    public PdpAuditLogEntry createAndSavePdpAuditLog(PepRequest pepRequest) {
        PdpAuditLogEntry logEntry = createPdpAuditLogFromPepRequest(pepRequest);
        return pdpAuditEntryRepository.save(logEntry);
    }

    private PdpAuditLogEntry createPdpAuditLogFromPepRequest(PepRequest pepRequest) {
        String pepRequestPayloadJSON;
        try {
            pepRequestPayloadJSON = JsonConverter.convertToJson(pepRequest);
        } catch (RuntimeException e) {
            log.error("Error in converting the pepRequest to JSON: {}", e.getMessage());
            throw new RuntimeException("Internal Server Error");
        }

        return PdpAuditLogEntry.builder()
                .requestId(pepRequest.getRequestId().trim())
                .requestedAt(LocalDateTime.now())
                .enterpriseName(pepRequest.getEnterpriseName().trim())
                .enterpriseId(pepRequest.getEnterpriseId())
                .pepRequestPayload(pepRequestPayloadJSON)
                .build();
    }

    public void updatePdpAuditLogFields(PdpAuditLogEntry logEntry, PdpDecision finalPdpDecision, PdpProcessedReason reason, List<String> opaFailedReasons) {
        logEntry.setFinalPdpDecision(finalPdpDecision);
        logEntry.setReason(reason);
        logEntry.setOpaFailedReasons(opaFailedReasons);
        logEntry.setRequestCompletedAt(LocalDateTime.now());
        pdpAuditEntryRepository.save(logEntry);
    }


    @Transactional
    public List<PdpAuditLogEntry> fetchAuditLogEntries(String enterpriseId) {
        return pdpAuditEntryRepository.findAllByEnterpriseIdOrderByRequestedAtDesc(enterpriseId);
    }
}
