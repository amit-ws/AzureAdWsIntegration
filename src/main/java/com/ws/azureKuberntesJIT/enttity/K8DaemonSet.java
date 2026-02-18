package com.ws.azureKuberntesJIT.enttity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "kubernetes_daemon_set", schema = "azure_test")
public class K8DaemonSet extends K8Metadata {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

//    Integer currentNumberScheduled;
//    Integer desiredNumberScheduled;
//    Integer numberAvailable;
//    Integer numberMisscheduled;
//    Integer numberReady;
//    Integer numberUnavailable;
//    Integer updatedNumberScheduled;
//    Integer collisionCount;
//    Long observedGeneration;
}