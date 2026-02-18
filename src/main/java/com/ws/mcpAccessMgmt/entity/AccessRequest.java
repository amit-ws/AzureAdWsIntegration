package com.ws.mcpAccessMgmt.entity;

import lombok.Data;

import java.time.LocalTime;

@Data
public class AccessRequest {
    private String userRole;
    private String resourceSensitivity;
    private LocalTime time;
    private double riskScore;
}
