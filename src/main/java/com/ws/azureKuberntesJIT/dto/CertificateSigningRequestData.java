package com.ws.azureKuberntesJIT.dto;


import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class CertificateSigningRequestData {
    String csrName;
    String csrPem;
    String csrBase64;
    String privateKeyPem;
    String privateKeyBase64;
    String userName;
    List<String> groups;
    Integer expirationSeconds;
    Long createdTimestamp;
    Long expirationTimestamp;
}