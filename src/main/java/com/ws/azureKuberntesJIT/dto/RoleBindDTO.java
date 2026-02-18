package com.ws.azureKuberntesJIT.dto;

import com.ws.azureKuberntesJIT.constant.K8ResourceLevel;
import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RoleBindDTO extends MetadataDTO{
    RoleRefDTO roleRefDTO;
    List<RbacSubjectDTO> rbacSubjectDTOS;
    K8ResourceLevel k8ResourceLevel;
}
