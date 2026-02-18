package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureKuberntesJIT.constant.K8RoleKind;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Embeddable
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@MappedSuperclass
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8RoleRequest {
    String roleId;  /* UUID of Role */
    String roleName;
    @Enumerated(EnumType.STRING)
    K8RoleKind roleKind;
    boolean isRoleCustomCreated;
    @Convert(converter = StringListConverter.class)
    @Column(name = "verbs", columnDefinition = "TEXT")
    List<String> verbs;
    String policyApiGroup;
    String policyResource;
    String policyResourceName;
}
