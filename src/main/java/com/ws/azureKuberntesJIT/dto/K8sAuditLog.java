package com.ws.azureKuberntesJIT.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class K8sAuditLog {
    public String time;
    public String username;
    public String verb;
    public String resource;
    public String subresource;
    public String namespace;
    public String status;
    public String sourceIp;
}
