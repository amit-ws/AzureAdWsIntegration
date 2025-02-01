package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserGroupMembership;
import com.ws.azureResourcesIntegration.dto.UserIdGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AzureUserGroupMembershipRepository extends JpaRepository<AzureUserGroupMembership, Integer> {
    @Query(value = "select au from AzureUser au LEFT JOIN AzureUserGroupMembership augm on au = augm.azureUser WHERE augm.azureGroup = :azureGroup")
    List<AzureUser> fetchUsersForGroup(AzureGroup azureGroup);

    @Query(value = "SELECT ag from AzureGroup ag LEFT JOIN AzureUserGroupMembership augm ON ag = augm.azureGroup WHERE augm.azureUser = :azureUser")
    List<AzureGroup> fetchGroupsForUser(AzureUser azureUser);

    @Query(value = "SELECT ag.display_name, augm.azure_user_id from azure_group ag LEFT JOIN azure_user_group_membership augm ON ag.id = augm.group_id  WHERE augm.azure_user_id  = ANY(:userIds)", nativeQuery = true)
    List<UserIdGroup> fetchGroupsForUsers(List<String> userIds);

    @Modifying
    void deleteById(Integer id);

    @Query(value = "SELECT * FROM azure_user_group_membership augm WHERE augm.azure_user_id = :azureUserId AND augm.azure_group_id = :azureGroupId", nativeQuery = true)
    Optional<AzureUserGroupMembership> findMemberShipUsingAzureUserIdAndAzureGroupId(String azureUserId, String azureGroupId);
}
