package com.ws.wsAgenticSecurityGateway.audit.config;

import com.ws.wsAgenticSecurityGateway.common.context.TenantContext;
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

    @Bean(name = "auditExecutor")
    public Executor auditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("gw-audit-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
                log.error("Audit executor rejected task — queue full. Consider increasing capacity."));
        // Propagate the caller's MDC (traceId / correlationId) AND tenant into the async audit thread so persisted
        // audit rows and audit-thread logs carry the same request trace and tenant as the orchestration that fired
        // them. The tenant is a plain ThreadLocal (TenantContext) that does not cross the async boundary on its own;
        // without it, a request-scoped audit write resolves its tenant on the worker thread — where TenantContext is
        // empty — and falls back to "system", which then hides the row from tenant-scoped reads (e.g. egress
        // reprocess looks the response body up by the request's own tenant). Snapshotting it here — exactly as MDC is
        // snapshotted — keeps the existing tenant model intact; nothing else about tenant resolution changes.
        executor.setTaskDecorator(runnable -> {
            java.util.Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
            String tenant = TenantContext.get();
            return () -> {
                java.util.Map<String, String> previousMdc = org.slf4j.MDC.getCopyOfContextMap();
                String previousTenant = TenantContext.get();
                if (mdc != null) {
                    org.slf4j.MDC.setContextMap(mdc);
                } else {
                    org.slf4j.MDC.clear();
                }
                if (tenant != null) {
                    TenantContext.set(tenant);
                } else {
                    TenantContext.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previousMdc != null) {
                        org.slf4j.MDC.setContextMap(previousMdc);
                    } else {
                        org.slf4j.MDC.clear();
                    }
                    if (previousTenant != null) {
                        TenantContext.set(previousTenant);
                    } else {
                        TenantContext.clear();
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
