package com.ws.mcpAgenticAIMgmt.dto;

import java.util.List;

public class Rule {
    public String ruleId;
    public String description;
    public Target target;
    public List<Condition> conditions;
    public String effect;
}