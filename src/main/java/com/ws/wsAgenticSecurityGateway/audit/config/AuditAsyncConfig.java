package com.ws.wsAgenticSecurityGateway.audit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async configuration dedicated to the MCP Audit Logging module.
 *
 * <p>All {@code @Async("mcpAuditExecutor")} methods execute on this
 * thread pool, ensuring audit persistence never blocks the hot path.
 */
@Configuration
@EnableAsync
@Slf4j
public class AuditAsyncConfig {

    @Bean(name = "mcpAuditExecutor")
    public Executor mcpAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mcp-audit-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("Audit executor rejected task — queue full. Consider increasing capacity."));
        executor.initialize();
        log.info("MCP Audit async executor initialized [core={}, max={}, queue={}]",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }
}
