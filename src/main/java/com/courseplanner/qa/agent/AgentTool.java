package com.courseplanner.qa.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 可调用的一种工具。返回值必须是 JSON——它会作为「观察结果」原样回灌给模型，
 * 所以不要返回给人看的自然语言长句。
 */
public interface AgentTool {

    String name();

    String description();

    /** 写进规划 prompt 的参数说明，例如 "programId（可选，默认当前方案）"。 */
    String argsHint();

    JsonNode execute(long userId, long programId, JsonNode args);
}
