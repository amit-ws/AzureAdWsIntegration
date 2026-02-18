package com.ws.azureKuberntesJIT.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SecretDTO extends MetadataDTO {
    String tyep;
    Boolean immutable;
    Map<String, String> stringData;
}
