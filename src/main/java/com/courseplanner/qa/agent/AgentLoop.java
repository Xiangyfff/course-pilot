package com.courseplanner.qa.agent;

import com.courseplanner.config.AppProperties;
import com.courseplanner.extract.LlmCallLogRepository;
import com.courseplanner.extract.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小的 Agent Loop：规划 → 调工具 → 观察 → 再规划，直到模型给出 final 或达到步数上限。
 *
 * 刻意不引入框架：整个循环只有一个 for、一次 JSON 解析、一张工具表。
 * 手写的好处是每个失败点都可见（模型选了不存在的工具、参数不合法、工具抛异常、JSON 不合法），
 * 这些失败点在评测里都要单独统计。
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 一次工具调用。observation 已截断，只为展示与评测。 */
    public record Step(String tool, String args, String observation, long ms) {
    }

    public record LoopResult(String text, String engine, List<Step> steps) {
    }

    private static final String PLANNER_SYSTEM = """
            你是一个工具规划器。根据学生的课程规划问题和已收集的信息，决定下一步调用哪个工具，或直接给出回答。
            只能输出一个 JSON 对象，不要解释、不要 markdown 代码块。
            调用工具：{"thought":"为什么调它","tool":"工具名","args":{...}}
            信息足够：{"thought":"...","final":"给学生的回答"}
            规则：
            1. 一次只调一个工具；
            2. 不要重复调用同一个工具且参数相同；
            3. 事实只能来自工具返回，不要编造课程、学分或政策。
            """;

    private static final String ANSWER_SYSTEM = """
            你是课程规划助教。只能使用给定的工具结果回答问题，不要编造课程、学分或政策。
            如果工具结果里没有答案，直接说「培养方案里没有写」。回答控制在 200 字以内，用中文。
            """;

    private final ToolRegistry registry;
    private final ObjectProvider<LlmClient> llm;
    private final LlmCallLogRepository llmLogs;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public AgentLoop(ToolRegistry registry, ObjectProvider<LlmClient> llm,
                     LlmCallLogRepository llmLogs, AppProperties props, ObjectMapper mapper) {
        this.registry = registry;
        this.llm = llm;
        this.llmLogs = llmLogs;
        this.props = props;
        this.mapper = mapper;
    }

    public LoopResult run(long userId, long programId, String question) {
        return run(userId, programId, question, List.of());
    }

    /** memories 是长期记忆（用户偏好），只拼进 prompt，不参与工具选择的事实判断。 */
    public LoopResult run(long userId, long programId, String question, List<String> memories) {
        LlmClient client = llm.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("没有可用的模型客户端，agent 模式需要 llm.provider=openai-compatible");
        }
        String memoryBlock = memories.isEmpty() ? "" : "\n用户偏好（长期记忆）：\n- " + String.join("\n- ", memories) + "\n";
        int maxSteps = Math.max(1, props.getQa().getMaxSteps());
        List<Step> steps = new ArrayList<>();
        StringBuilder observations = new StringBuilder();
        String model = "unknown";
        String finalText = null;
        int invalidOutputs = 0;
        String lastSignature = null;

        for (int round = 1; round <= maxSteps; round++) {
            String prompt = toolCatalog()
                    + "\n已收集的信息：\n" + (observations.isEmpty() ? "（还没有）" : observations)
                    + memoryBlock
                    + "\n\n学生问题：" + question;
            LlmClient.Result plan = client.chat(
                    new LlmClient.Request("qa-agent-plan", PLANNER_SYSTEM, prompt, true));
            llmLogs.record(userId, null, "qa-agent-plan", plan);
            model = plan.model();

            JsonNode node = tryParse(plan.content());
            if (node == null) {
                invalidOutputs++;
                observations.append("\n[系统] 你的上一步输出不是合法 JSON，请只输出一个 JSON 对象。");
                if (invalidOutputs >= 2) {
                    break;
                }
                continue;
            }
            String planned = node.path("final").asText("");
            if (!planned.isBlank()) {
                finalText = planned;
                break;
            }
            String toolName = node.path("tool").asText("");
            JsonNode args = node.path("args");
            AgentTool tool = registry.byName(toolName);
            if (tool == null) {
                observations.append("\n[系统] 不存在工具「").append(toolName)
                        .append("」，可用工具：").append(registry.all().stream().map(AgentTool::name).toList());
                continue;
            }
            long started = System.currentTimeMillis();
            String signature = toolName + "|" + (args == null ? "{}" : args.toString());
            if (signature.equals(lastSignature)) {
                // 模型在原地打转：同一工具同一参数再来一次，结果不会变，直接收束，别再烧 token。
                observations.append("\n[系统] 你已经用相同参数调用过「").append(toolName)
                        .append("」，结果不会变化，请基于已有信息直接作答。");
                break;
            }
            lastSignature = signature;
            String observation;
            try {
                observation = tool.execute(userId, programId, args).toString();
            } catch (Exception e) {
                observation = "{\"error\":\"" + e.getMessage() + "\"}";
                log.warn("agent tool {} failed", toolName, e);
            }
            long ms = System.currentTimeMillis() - started;
            String trimmed = observation.length() > 1200 ? observation.substring(0, 1200) + "…" : observation;
            steps.add(new Step(toolName, args == null ? "{}" : args.toString(), trimmed, ms));
            observations.append("\n[第").append(steps.size()).append("步] ")
                    .append(toolName).append(" -> ").append(trimmed);
        }

        if (finalText == null) {
            String prompt = "工具结果：\n" + (observations.isEmpty() ? "（没有拿到任何工具结果）" : observations)
                    + memoryBlock
                    + "\n\n学生问题：" + question;
            LlmClient.Result answer = client.chat(
                    new LlmClient.Request("qa-agent-answer", ANSWER_SYSTEM, prompt, false));
            llmLogs.record(userId, null, "qa-agent-answer", answer);
            model = answer.model();
            finalText = answer.content().trim();
        }
        return new LoopResult(finalText, "agent:" + model, steps);
    }

    private String toolCatalog() {
        StringBuilder sb = new StringBuilder("可用工具：\n");
        for (AgentTool t : registry.all()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description())
                    .append(" 参数: ").append(t.argsHint()).append('\n');
        }
        return sb.toString();
    }

    private JsonNode tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                text = text.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
