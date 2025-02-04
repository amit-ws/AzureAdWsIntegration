package com.ws.azureResourcesIntegration.dto;

import com.ws.azureResourcesIntegration.constant.RequestStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ProcessAccessRequest {
    @NotNull(message = "Please provide STATUS to update")
    RequestStatus status;
    @NotEmpty(message = "Pleaee provide request IDs to process")
    List<String> assignmentIds;
}
