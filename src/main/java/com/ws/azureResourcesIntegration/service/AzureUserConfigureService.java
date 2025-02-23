package com.ws.azureResourcesIntegration.service;

import com.microsoft.graph.models.User;
import com.ws.azureAdIntegration.constants.Constant;
import com.ws.azureAdIntegration.dto.AzureUserCredentialDTO;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.repository.AzureUserRepository;
import com.ws.azureAdIntegration.service.AzureADInitializerService;
import com.ws.azureAdIntegration.service.AzureADService;
import com.ws.azureAdIntegration.service.AzureUserCredentialService;
import com.ws.azureAdIntegration.service.BackendApplicationLogservice;
import com.ws.azureAdIntegration.util.GenericUtil;
import com.ws.azureResourcesIntegration.dto.AzureUserConfigureRequest;
import com.ws.azureResourcesIntegration.entities.AzureUserConfigure;
import com.ws.azureResourcesIntegration.repository.AzureUserConfigureRepository;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.Date;
import java.util.Optional;

@Service
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserConfigureService {
    final AzureUserConfigureRepository azureUserConfigureRepository;
    final AzureUserCredentialService azureUserCredentialService;
    final AzureUserRepository azureUserRepository;
    final AzureADInitializerService azureADInitializerService;
    final BackendApplicationLogservice backendApplicationLogservice;


    @Autowired
    public AzureUserConfigureService(AzureUserConfigureRepository azureUserConfigureRepository, AzureUserCredentialService azureUserCredentialService, AzureUserRepository azureUserRepository,
                                     AzureADInitializerService azureADInitializerService, BackendApplicationLogservice backendApplicationLogservice) {
        this.azureUserConfigureRepository = azureUserConfigureRepository;
        this.azureUserCredentialService = azureUserCredentialService;
        this.azureUserRepository = azureUserRepository;
        this.azureADInitializerService = azureADInitializerService;
        this.backendApplicationLogservice = backendApplicationLogservice;
    }

    public AzureUserConfigure findByUserEmailAndWsTenantName(String email, String wsTenantName) {
        return azureUserConfigureRepository.findByEmailAndWsTenantName(email, wsTenantName)
                .orElseThrow(() -> new RuntimeException(String.format("No azure user found with provided email: %s. Kindly configure the user first.", email)));
    }


    @Transactional
    public AzureUserConfigure configureAzureUser(AzureUserConfigureRequest request) {
        String azureId = request.getAzureId().trim();
        String wsTenantName = request.getWsTenantName().trim();
        String displayName = request.getDisplayName().trim();
        String userEmail = request.getEmail().trim();
        if (azureUserConfigureRepository.existsByAzureIdOrEmail(azureId, userEmail)) {
            throw new RuntimeException(String.format("User already configured with the provided email: %s Or azure-id: %s", userEmail, azureId));
        }

        if (azureUserRepository.findByAzureId(azureId).isEmpty()) {
            AzureUserCredentialDTO credentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName);
            try {
                azureADInitializerService.initializeGraphClient(credentialDTO, null);
                User user = azureADInitializerService.findUserByUserId(azureId);
                if (ObjectUtils.isEmpty(user)) {
                    throw new RuntimeException(String.format("No user found. Invalid azure id provided. Id: %s", azureId));
                }
                azureId = user.id;
            } catch (Exception ex) {
                if (ex.getMessage().contains("Request_ResourceNotFound")) {
                    log.error("Message: {}", "Provided azure-id and Ws-tenant-name mismatched");
                    throw new RuntimeException(String.format("Invalid azure id provided. Id: %s", azureId));
                }
                throw new RuntimeException("Unexpected error occurred: " + ex.getMessage());
            }
        }

        AzureUserConfigure savedUserConfig = azureUserConfigureRepository.save(AzureUserConfigure.builder()
                .azureId(azureId)
                .wsTenantName(wsTenantName)
                .displayName(GenericUtil.getOrNull(() -> request.getDisplayName().trim()))
                .email(request.getEmail().trim())
                .createdOn(new Date())
                .build());
        backendApplicationLogservice.saveAuditLog(wsTenantName, displayName, Constant.ADD, String.format("Added aws user config with username: %s and azure-id: %s", displayName, azureId), "Info");
        return savedUserConfig;
    }


    public Object findUser(String id, String tenantName) {
        AzureUserCredentialDTO credentialDTO = azureUserCredentialService.findWSTenantIdWithDecryptedSecret(tenantName);
        azureADInitializerService.initializeGraphClient(credentialDTO, null);
        User user = azureADInitializerService.findUserByUserId(id);
        return user.displayName;
    }


//    @Transactional
//    public AzureUserConfigure configureAzureUser(AzureUserConfigureRequest request) {
//        String azureId = request.getAzureId().trim();
//        String wsTenantName = request.getWsTenantName().trim();
//        String displayName = request.getDisplayName().trim();
//        Optional<AzureUserConfigure> azureUserConfigureOpt = azureUserConfigureRepository.findByWsTenantNameAndAzureId(wsTenantName, azureId);
//        return azureUserConfigureOpt.orElseGet(() -> {
//            if (validateUser(displayName, azureId, wsTenantName)) {
//                AzureUserConfigure savedUserConfig = azureUserConfigureRepository.save(AzureUserConfigure.builder()
//                        .azureId(azureId)
//                        .wsTenantName(wsTenantName)
//                        .displayName(displayName)
//                        .email(request.getEmail().trim())
//                        .createdOn(new Date())
//                        .build());
//
//                backendApplicationLogservice.saveAuditLog(wsTenantName, displayName, Constant.ADD, String.format("Added aws user config with username: %s and azure-id: %s", displayName, azureId), "Info");
//                return savedUserConfig;
//            } else {
//                throw new RuntimeException("Invalid user or configured in other accounts");
//            }
//        });
//    }
//
//
//    private boolean validateUser(@NotNull String displayName, @NotNull String azureId, @NotNull String wsTenantName) {
//        return Optional.ofNullable(azureUserCredentialService.findWSTenantIdWithDecryptedSecret(wsTenantName))
//                .map(azureUserCredentialDTO -> {
//                    try {
//                        azureADInitializerService.initializeGraphClient(azureUserCredentialDTO, null);
//                        User user = azureADInitializerService.findUserByUserId(azureId);
//                        return user != null && displayName != null && displayName.equalsIgnoreCase(user.displayName);
//                    } catch (Exception exp) {
//                        log.error("Azure error while validating User using azure-id: {}", azureId, exp);
//                        return false;
//                    }
//                })
//                .orElse(false);
//    }

}
