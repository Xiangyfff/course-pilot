package com.courseplanner.qa.agent;

import com.courseplanner.solve.SolveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 查学分进度：总学分、剩余学分、各模块缺口、未完成必修门数。 */
@Component
public class ProgressTool implements AgentTool {

    private final SolveService solveService;
    private final ObjectMapper mapper;

    public ProgressTool(SolveService solveService, ObjectMapper mapper) {
        this.solveService = solveService;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_progress";
    }

    @Override
    public String description() {
        return "查询当前培养方案下的学分进度：总要求、已修、还差、各模块缺口、未完成必修课门数";
    }

    @Override
    public String argsHint() {
        return "无参数，传 {}";
    }

    @Override
    public JsonNode execute(long userId, long programId, JsonNode args) {
        SolveService.Dashboard d = solveService.dashboard(userId, programId);
        ObjectNode n = mapper.createObjectNode();
        n.put("major", d.major());
        n.put("requiredCredits", d.requiredCredits());
        n.put("earnedCredits", d.earnedCredits());
        n.put("remainingCredits", d.remainingCredits());
        n.put("remainingCourses", d.remainingCourses());
        ArrayNode modules = n.putArray("modules");
        for (SolveService.ModuleProgress m : d.modules()) {
            modules.addObject()
                    .put("name", m.name())
                    .put("required", m.required())
                    .put("earned", m.earned())
                    .put("remaining", m.remaining());
        }
        return n;
    }
}
