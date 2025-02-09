package com.ws.azureKuberntesJIT.dto;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StorageClassDTO extends MetadataDTO {
    String apiVersion;
    String provisioner;
    String volumeBindingMode;
    Boolean allowVolumeExpansion;
    String reclaimPolicy;
}
