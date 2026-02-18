package com.ws.azureKuberntesJIT.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PolicyRuleDTO {
    List<String> verbs;
    List<String> apiGroups;
    List<String> resources;
    List<String> nonResourceURLs;
    List<String> resourceNames;
}
