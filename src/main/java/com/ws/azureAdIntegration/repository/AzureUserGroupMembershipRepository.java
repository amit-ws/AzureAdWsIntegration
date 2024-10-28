package com.ws.azureAdIntegration.repository;

import com.ws.azureAdIntegration.entity.AzureUserGroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;

public interface AzureUserGroupMembershipRepository extends JpaRepository<AzureUserGroupMembership, Integer> {

    @Query(value = "select u.azure_id as userId, u.display_name as userName, g.azure_id as groupId, g.display_name as groupName from \"user\" u inner join user_group_membership ugm on u.id = ugm.user_id inner join \"group\" g on ugm.group_id = g.id"
            , nativeQuery = true)
    List<Map<String, String>> fetchUsersGroupsMembership();


    @Query(value = "select au.* from azure_user au left join azure_user_group_membership augm on au.id = augm.user_id where augm.group_id = :groupId",
    nativeQuery = true)
    List<Map<String, Object> >fetchUsersForGroup(Integer groupId);

}
