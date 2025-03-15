package com.ws.azureKuberntesJIT.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class RoleResponse {
    String roleId;
    String roleName;
    String roleType;
}
