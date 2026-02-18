package com.ws.logMcpServer.service;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LogColumnData {
    String columnName;
    Object columnValue;
    String columnType;
}
