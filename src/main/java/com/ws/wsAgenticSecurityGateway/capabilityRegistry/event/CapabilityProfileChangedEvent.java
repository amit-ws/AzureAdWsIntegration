package com.ws.wsAgenticSecurityGateway.capabilityRegistry.event;

import java.util.List;
import java.util.UUID;

public class CapabilityProfileChangedEvent {

    private final String reason;
    private final String profileName;
    private final UUID profileId;
    private final List<String> affectedAgentNames;

    public CapabilityProfileChangedEvent(String reason, String profileName, UUID profileId,
                                          List<String> affectedAgentNames) {
        this.reason = reason;
        this.profileName = profileName;
        this.profileId = profileId;
        this.affectedAgentNames = affectedAgentNames;
    }

    public String getReason() { return reason; }
    public String getProfileName() { return profileName; }
    public UUID getProfileId() { return profileId; }
    public List<String> getAffectedAgentNames() { return affectedAgentNames; }
}
