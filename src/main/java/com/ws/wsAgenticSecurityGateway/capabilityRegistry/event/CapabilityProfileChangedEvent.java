package com.ws.wsAgenticSecurityGateway.capabilityRegistry.event;

import java.util.List;
import java.util.UUID;

/**
 * Application event emitted when a capability profile is assigned, unassigned,
 * updated, or deleted — indicating that connected agents should be notified
 * to re-fetch their tool/prompt/resource lists.
 */
public class CapabilityProfileChangedEvent {

    private final String reason;          // PROFILE_ASSIGNED, PROFILE_UNASSIGNED, PROFILE_UPDATED, PROFILE_DELETED
    private final String profileName;
    private final UUID profileId;
    private final List<String> affectedAgentNames;  // agents whose effective access changed

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
