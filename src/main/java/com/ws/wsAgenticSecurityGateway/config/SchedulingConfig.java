package com.ws.wsAgenticSecurityGateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support for periodic tasks
 * such as the idle session reaper and WS Client-side health checker.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
