package com.ws.azureKuberntesJIT.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class NamespaceDTO extends MetadataDTO {
    String generateName;

//    String conditionType;
//    String conditionReason;
//    String conditionStatus;
//    String conditionMessage;
//    List<String> finalizers;
//    Map<String, String> labels;
//    Long deletionGracePeriodSeconds;
//    Boolean ownerReferencesController;
//    String managedFieldsType;
//    String managedFieldsManager;
//    String managedFieldsOperation;
//    String managedFieldsSubresource;
}
