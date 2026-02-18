package com.ws.azureKuberntesJIT.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PersistentVolumeClaimDTO extends MetadataDTO {
    String apiVersion;
    String provisioner;
    String volumeBindingMode;
    Boolean allowVolumeExpansion;
    String reclaimPolicy;
}
