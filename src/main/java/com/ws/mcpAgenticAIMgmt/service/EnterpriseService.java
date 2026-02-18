package com.ws.mcpAgenticAIMgmt.service;

import com.ws.mcpAgenticAIMgmt.constant.EnterpriseStatus;
import com.ws.mcpAgenticAIMgmt.exception.WsAgenticAIMgmtException;
import com.ws.mcpAgenticAIMgmt.model.Enterprise;
import com.ws.mcpAgenticAIMgmt.repository.EnterpriseRepository;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
@FieldDefaults(level = AccessLevel.PRIVATE)
public class EnterpriseService {
    final EnterpriseRepository enterpriseRepository;

    @Autowired
    public EnterpriseService(EnterpriseRepository enterpriseRepository) {
        this.enterpriseRepository = enterpriseRepository;
    }


    @Transactional
    public Map<String, String> onboardEnterprise(Enterprise enterprise) {
        if (ObjectUtils.isEmpty(enterprise)) {
            throw new WsAgenticAIMgmtException("No Payload provided");
        }
        if (ObjectUtils.isNotEmpty(getByEmail(enterprise.getContactEmail().trim()))) {
            throw new WsAgenticAIMgmtException("Enterprise already onboard with provided email: " + enterprise.getContactEmail());
        }
        enterprise.setStatus(EnterpriseStatus.ACTIVE);
        enterpriseRepository.save(enterprise);
        return Map.of("email", enterprise.getContactEmail());
    }

    public Enterprise findEnterprise(String email) {
        if (StringUtils.isEmpty(email)) {
            throw new WsAgenticAIMgmtException("No email provided");
        }
        Enterprise enterprise = getByEmail(email);
        log.info("Enterprise: {}", enterprise.getContactEmail());
        return enterprise;
    }


    private Enterprise getByEmail(String email) {
        return enterpriseRepository.findByContactEmail(email).orElse(null);
    }


}
