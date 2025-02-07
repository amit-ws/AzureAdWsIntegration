package com.ws.azureKuberntesJIT.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class NodeDTO extends MetadataDTO{
    String externalID;
    String podCIDR;
    Boolean unschedulable;
    String providerID;
}
