package com.ws.azureResourcesIntegration.config;

import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentEvents;
import com.ws.azureResourcesIntegration.constant.CustomRoleAssignmentStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachine;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

@Configuration
@EnableStateMachine
public class RoleAssignmentStateMachineConfig extends StateMachineConfigurerAdapter<CustomRoleAssignmentStatus, CustomRoleAssignmentEvents> {

    @Override
    public void configure(StateMachineStateConfigurer<CustomRoleAssignmentStatus, CustomRoleAssignmentEvents> states) throws Exception {
        states
                .withStates()
                .initial(CustomRoleAssignmentStatus.REQUESTED)
                .states(EnumSet.allOf(CustomRoleAssignmentStatus.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<CustomRoleAssignmentStatus, CustomRoleAssignmentEvents> transitions) throws Exception {
        transitions
                .withExternal().source(CustomRoleAssignmentStatus.REQUESTED).target(CustomRoleAssignmentStatus.DENIED).event(CustomRoleAssignmentEvents.DENY)
                .and()
                .withExternal().source(CustomRoleAssignmentStatus.REQUESTED).target(CustomRoleAssignmentStatus.APPROVED).event(CustomRoleAssignmentEvents.APPROVE)
                .and()
                .withExternal().source(CustomRoleAssignmentStatus.DENIED).target(CustomRoleAssignmentStatus.APPROVED).event(CustomRoleAssignmentEvents.APPROVE)
                .and()
                .withExternal().source(CustomRoleAssignmentStatus.APPROVED).target(CustomRoleAssignmentStatus.EXPIRED).event(CustomRoleAssignmentEvents.EXPIRE);
    }
}
