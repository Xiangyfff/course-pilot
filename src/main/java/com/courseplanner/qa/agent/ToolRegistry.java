package com.courseplanner.qa.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 所有 AgentTool 的注册表，顺序即 prompt 里的排列顺序。 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> m = new LinkedHashMap<>();
        for (AgentTool t : tools) {
            m.put(t.name(), t);
        }
        this.tools = Map.copyOf(m);
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }

    public AgentTool byName(String name) {
        return name == null ? null : tools.get(name);
    }
}
