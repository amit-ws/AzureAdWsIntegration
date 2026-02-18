package com.ws.certificateJIT.k8;


import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.Instant;
import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "k8_certificate_access_grant", schema = "azure_test")
@FieldDefaults(level = AccessLevel.PRIVATE)
public class JitAccessGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    String saName;
    String namespace;
    String jitIdentity;
    String targetResource;
    Instant expiresAt;
    Instant rotatedAt;
    String csrName;
    String secretName;
    String roleName;
    String bindingName;

    Date createdAt;
}
