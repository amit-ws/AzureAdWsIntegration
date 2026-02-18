package com.ws.certificateJIT.k8;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessRequest {
    private String userId;
    private String userName;
    private String namespace;
//    private String roleName;
    private Integer durationSeconds;
    private List<String> groups;
    private List<String> verbs;
    private String resource;
}