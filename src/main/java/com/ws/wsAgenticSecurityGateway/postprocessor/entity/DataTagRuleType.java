package com.ws.wsAgenticSecurityGateway.postprocessor.entity;

/**
 * The three ways an admin rule adjusts the built-in egress classifier.
 *
 * <ul>
 *   <li>{@code CUSTOM}   — add a new detector: a regex that tags matches with a category + sensitivity.</li>
 *   <li>{@code DISABLE}  — turn off a built-in detector (by its matcher id).</li>
 *   <li>{@code OVERRIDE} — remap a built-in detector's category and/or sensitivity.</li>
 * </ul>
 */
public enum DataTagRuleType {
    CUSTOM,
    DISABLE,
    OVERRIDE
}
