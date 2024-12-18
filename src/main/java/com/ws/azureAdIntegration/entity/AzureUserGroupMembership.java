package com.ws.azureAdIntegration.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "azure_user_group_membership", schema = "azure_test")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AzureUserGroupMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Integer id;

    @ManyToOne
    @JoinColumn(name = "user_id", referencedColumnName = "id")
    AzureUser azureUser;
    String azureUserId;

    @ManyToOne
    @JoinColumn(name = "group_id", referencedColumnName = "id")
    AzureGroup azureGroup;
    String azureGroupId;
}
