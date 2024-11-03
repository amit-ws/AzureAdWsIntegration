package com.ws.azureAdIntegration.entity;

import lombok.*;

import jakarta.persistence.Entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "backend_application_log", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BackendApplicationLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;
    Integer wsTenantId;
    String logLevel;
    String message;
    String loggedInUserEmail;
    String action;
    LocalDateTime time;
}
