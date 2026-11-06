package com.courseplanner.qa.agent;

import com.courseplanner.solve.PlanModels;
import com.courseplanner.solve.SolveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 读最近一次排课方案。注意这里是「读」不是「重新求解」——
 * 求解是异步任务，agent 循环里不能让模型等一个后台任务。
 */
@Component
public class PlanTool implements AgentTool {

    private final SolveService solveService;
    private final ObjectMapper mapper;

    public PlanTool(SolveService solveService, ObjectMapper mapper) {
        this.solveService = solveService;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "solve_plan";
    }

    @Override
    public String description() {
        return "读取最近一次生成的排课方案：每学期上哪些课、总学分、风险分。若还没有方案会返回空列表";
    }

    @Override
    public String argsHint() {
        return "无参数，传 {}";
    }

    @Override
    public JsonNode execute(long userId, long programId, JsonNode args) {
        List<PlanModels.Plan> plans = solveService.latest(userId, programId);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("plans");
        if (plans.isEmpty()) {
            out.put("hint", "还没有生成排课方案，用户需要先在「排课方案」页点一次生成");
            return out;
        }
        for (PlanModels.Plan p : plans) {
            ObjectNode node = arr.addObject();
            node.put("index", p.index());
            node.put("semesters", p.semesters());
            node.put("totalCredits", p.totalCredits());
            node.put("riskScore", p.riskScore());
            ArrayNode terms = node.putArray("terms");
            for (PlanModels.Term t : p.terms()) {
                ObjectNode tn = terms.addObject();
                tn.put("term", t.term());
                tn.put("credits", t.credits());
                ArrayNode courses = tn.putArray("courses");
                for (PlanModels.Item it : t.courses()) {
                    courses.add(it.code() + " " + it.name());
                }
            }
            if (!p.notes().isEmpty()) {
                ArrayNode notes = node.putArray("notes");
                p.notes().forEach(notes::add);
            }
        }
        return out;
    }
}
