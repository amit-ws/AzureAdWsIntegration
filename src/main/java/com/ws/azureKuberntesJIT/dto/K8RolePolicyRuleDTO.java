package com.ws.azureKuberntesJIT.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class K8RolePolicyRuleDTO {
    Long id;
    String roleUID;
    List<String> verbs;
    List<String> apiGroups;
    List<String> resources;
    List<String> nonResourceURLs;
    List<String> resourceNames;

}
