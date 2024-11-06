package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureGroup;
import com.ws.azureAdIntegration.entity.AzureUser;
import com.ws.azureAdIntegration.entity.AzureUserGroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;

public interface AzureUserGroupMembershipRepository extends JpaRepository<AzureUserGroupMembership, Integer> {
    @Query(value = "select au from AzureUser au LEFT JOIN AzureUserGroupMembership augm on au = augm.azureUser WHERE augm.azureGroup = :azureGroup")
    List<AzureUser> fetchUsersForGroup(AzureGroup azureGroup);

    @Query(value = "SELECT ag from AzureGroup ag LEFT JOIN AzureUserGroupMembership augm ON ag = augm.azureGroup WHERE augm.azureUser = :azureUser")
    List<AzureGroup> fetchGroupsForUser(AzureUser azureUser);


    void deleteByAzureUser(AzureUser azureUser);
}
