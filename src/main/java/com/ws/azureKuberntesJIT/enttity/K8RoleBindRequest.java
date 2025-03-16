package com.ws.azureKuberntesJIT.enttity;

import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import com.ws.azureKuberntesJIT.constant.K8ResourceType;
import com.ws.azureKuberntesJIT.constant.K8RoleBindingType;
import com.ws.azureKuberntesJIT.constant.K8SubjectKind;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.MappedSuperclass;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Embeddable
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@MappedSuperclass
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8RoleBindRequest {
    @Column(name = "kubernetes_resource_id")
    String k8ResourceId;  /* UUID of the resource */
    @Column(name = "kubernetes_resource_name")
    String k8ResourceName;
    K8ResourceType resourceType; /* K8ResourceType (basically of what resource type the binding has been created for) */
    String roleBindingId; /* UUID from metadata object of Role binding object sent by K8 */
    @Column(nullable = false)
    String roleBindingName; /* User (or BE) defined role binding name */
    @Column(nullable = false)
    K8RoleBindingType bindingType;
    @Column(nullable = false)
    K8SubjectKind subjectKind;
    @Column(nullable = false)
    String userName; /* For azure users -> UPN */
    String namespace;
    @Column(nullable = false)
    K8ResourceLevel level;
}
