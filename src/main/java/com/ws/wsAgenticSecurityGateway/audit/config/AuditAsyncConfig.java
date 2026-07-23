package com.ws.wsAgenticSecurityGateway.audit.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@Slf4j
public class AuditAsyncConfig {

    @Bean(name = "mcpAuditExecutor")
    public Executor mcpAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("mcp-audit-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("Audit executor rejected task — queue full. Consider increasing capacity."));
        // Propagate the caller's MDC (traceId / correlationId) into the async audit thread so persisted
        // audit rows and audit-thread logs carry the same request trace as the orchestration that fired them.
        executor.setTaskDecorator(runnable -> {
            java.util.Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
            return () -> {
                java.util.Map<String, String> previous = org.slf4j.MDC.getCopyOfContextMap();
                if (mdc != null) {
                    org.slf4j.MDC.setContextMap(mdc);
                } else {
                    org.slf4j.MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        org.slf4j.MDC.setContextMap(previous);
                    } else {
                        org.slf4j.MDC.clear();
                    }
                }
            };
        });
        executor.initialize();
        log.info("MCP Audit async executor initialized [core={}, max={}, queue={}]",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }
}
