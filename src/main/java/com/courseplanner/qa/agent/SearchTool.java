package com.courseplanner.qa.agent;

import com.courseplanner.common.TextSearch;
import com.courseplanner.program.ProgramRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** 在培养方案原文里检索片段，结果会作为引用回给前端。 */
@Component
public class SearchTool implements AgentTool {

    private static final int LIMIT = 5;

    private final ProgramRepository programs;
    private final ObjectMapper mapper;

    public SearchTool(ProgramRepository programs, ObjectMapper mapper) {
        this.programs = programs;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "search_program";
    }

    @Override
    public String description() {
        return "在培养方案原文里按关键词检索相关段落，用于回答课程名称、学分、先修、模块要求等问题";
    }

    @Override
    public String argsHint() {
        return "keyword（必填，字符串，例如 \"通识教育\"、\"先修\"）";
    }

    @Override
    public JsonNode execute(long userId, long programId, JsonNode args) {
        String keyword = args == null ? "" : args.path("keyword").asText("");
        ObjectNode out = mapper.createObjectNode();
        out.put("keyword", keyword);
        ArrayNode results = out.putArray("results");
        ProgramRepository.ProgramRow program = programs.findById(programId).orElse(null);
        if (program == null || keyword.isBlank()) {
            return out;
        }
        for (String line : TextSearch.search(program.sourceText(), keyword, LIMIT)) {
            results.addObject()
                    .put("source", program.sourceFile() == null ? "培养方案" : program.sourceFile())
                    .put("snippet", line);
        }
        return out;
    }
}
