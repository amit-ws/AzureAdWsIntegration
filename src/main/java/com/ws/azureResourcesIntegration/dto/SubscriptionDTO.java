package com.ws.azureResourcesIntegration.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class SubscriptionDTO {
    String subscriptionId;
    String subscriptionName;
    String subscriptionState;
    String spendingLimit;
}
