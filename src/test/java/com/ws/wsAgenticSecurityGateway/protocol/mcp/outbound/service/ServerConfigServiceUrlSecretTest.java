package com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ws.wsAgenticSecurityGateway.audit.service.GatewayAuditService;
import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
import com.ws.wsAgenticSecurityGateway.common.crypto.SecretCryptoService;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpServerConfig;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.config.McpSessionManager;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto.ServerConfigRequest;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.dto.ServerConfigResponse;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.entity.GatewayServerConfigEntity;
import com.ws.wsAgenticSecurityGateway.protocol.mcp.outbound.repository.GatewayServerConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An API key placed in a server URL (e.g. {@code ?apikey=KEY}) must be encrypted at rest, masked in API responses,
 * and decrypted only for the outbound connection — the same contract the secret HEADERS already honour. This pins
 * that round-trip so a key never sits in the DB in plaintext.
 */
class ServerConfigServiceUrlSecretTest {

    private static final String SECRET = "SECRET12345apikeyvalue";

    private final GatewayServerConfigRepository repo = mock(GatewayServerConfigRepository.class);
    private final McpSessionManager sessionManager = mock(McpSessionManager.class);
    private final GatewayAuditService audit = mock(GatewayAuditService.class);
    // Real crypto with a fixed test key — exercises the actual encrypt/decrypt, not a stub.
    private final SecretCryptoService crypto = new SecretCryptoService("unit-test-encryption-key-0123456789");
    private final ServerConfigService service =
            new ServerConfigService(repo, sessionManager, audit, crypto, new ObjectMapper());

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        when(repo.existsByServerNameAndWsTenantName(anyString(), anyString())).thenReturn(false);
        when(repo.save(any(GatewayServerConfigEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(sessionManager.isConnected(anyString())).thenReturn(false);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void urlApiKey_isEncryptedAtRest_maskedInResponse_decryptedForConnection() throws Exception {
        ServerConfigRequest req = ServerConfigRequest.builder()
                .serverName("alphavantage")
                .type("http")
                .url("https://mcp.alphavantage.co/mcp?apikey=" + SECRET)
                .autoConnect(false)   // skip auto-connect on create
                .enabled(true)
                .build();

        ServerConfigResponse resp = service.createServerConfig(req);

        // 1) At rest: the persisted URL must NOT contain the plaintext key; it carries the ENCv1 token instead.
        ArgumentCaptor<GatewayServerConfigEntity> saved = ArgumentCaptor.forClass(GatewayServerConfigEntity.class);
        verify(repo).save(saved.capture());
        String storedUrl = saved.getValue().getUrl();
        assertThat(storedUrl).doesNotContain(SECRET);
        assertThat(storedUrl).contains("ENCv1");

        // 2) In the API response: masked, never the real key.
        assertThat(resp.getUrl()).doesNotContain(SECRET);
        assertThat(resp.getUrl()).contains("REDACTED");

        // 3) For the outbound connection: decrypted back to the real key.
        service.connectFromConfig(saved.getValue());
        ArgumentCaptor<McpServerConfig> cfg = ArgumentCaptor.forClass(McpServerConfig.class);
        verify(sessionManager).connect(eq("alphavantage"), cfg.capture(), anyString());
        assertThat(cfg.getValue().getUrl()).contains(SECRET);
    }
}
