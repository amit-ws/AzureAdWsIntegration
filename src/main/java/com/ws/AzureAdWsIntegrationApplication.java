package com.ws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class AzureAdWsIntegrationApplication {
    public static void main(String[] args) {
        SpringApplication.run(AzureAdWsIntegrationApplication.class, args);
    }
}
