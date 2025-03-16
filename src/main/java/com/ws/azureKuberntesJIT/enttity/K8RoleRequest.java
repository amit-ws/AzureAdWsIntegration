package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureKuberntesJIT.constant.K8RoleKind;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.MappedSuperclass;
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
    K8RoleKind roleKind;
    boolean isRoleCustomCreated;
    @Convert(converter = StringListConverter.class)
    @Column(name = "verbs", columnDefinition = "TEXT")
    List<String> verbs;
    String apiGroup;
    String resource;
    String resourcesName;
}
