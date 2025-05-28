package com.ws.mcpAgenticAIMgmt.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PdpResponse {
    private boolean decision;
    private String message;
    private String requestId;
    private List<String> failedReasons;
}
