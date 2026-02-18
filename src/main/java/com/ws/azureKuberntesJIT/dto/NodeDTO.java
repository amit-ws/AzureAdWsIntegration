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
public class NodeDTO extends MetadataDTO{
    String externalID;
    String podCIDR;
    Boolean unschedulable;
    String providerID;
}
