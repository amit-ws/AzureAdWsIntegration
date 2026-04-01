package com.ws.wsAgenticSecurityGateway.audit.error;

import lombok.Getter;

@Getter
public class McpAuditException extends RuntimeException {

    private final McpErrorCode errorCode;
    private final Object errorData;

    public McpAuditException(McpErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.errorData = null;
    }

    public McpAuditException(McpErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
        this.errorData = null;
    }

    public McpAuditException(McpErrorCode errorCode, String detail, Object errorData) {
        super(detail);
        this.errorCode = errorCode;
        this.errorData = errorData;
    }

    public McpAuditException(McpErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
        this.errorData = null;
    }
}
