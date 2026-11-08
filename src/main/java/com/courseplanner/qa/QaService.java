package com.courseplanner.qa;

import com.courseplanner.common.BizException;
import com.courseplanner.config.AppProperties;
import com.courseplanner.extract.LlmBackedExtractor;
import com.courseplanner.extract.LlmCallLogRepository;
import com.courseplanner.extract.LlmClient;
import com.courseplanner.program.ProgramRepository;
import com.courseplanner.qa.agent.AgentLoop;
import com.courseplanner.qa.memory.MemoryService;
import com.courseplanner.solve.PlanModels;
import com.courseplanner.solve.PlanResultRepository;
import com.courseplanner.solve.SolveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 问答：先查结构化事实（学分进度、最近一次方案），再检索原文片段，
 * 最后才让模型组织语言。模型只负责「怎么说」，不负责「是什么」。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    public record Citation(String source, String snippet) {
    }

    /** 上一轮问答，用于解析「那按这个排呢」这类指代。 */
    public record Turn(String question, String answer) {
    }

    private static final int HISTORY_LIMIT = 3;
    private static final int HISTORY_ANSWER_CLIP = 120;

    public record Answer(String text, List<Citation> citations, String engine, List<AgentLoop.Step> steps) {
        public Answer(String text, List<Citation> citations, String engine) {
            this(text, citations, engine, List.of());
        }
    }

    private final ProgramRepository programs;
    private final SolveService solveService;
    private final PlanResultRepository planResults;
    private final ObjectProvider<LlmClient> llm;
    private final LlmCallLogRepository llmLogs;
    private final QaRepository qaRepo;
    private final AppProperties props;
    private final AgentLoop agentLoop;
    private final MemoryService memoryService;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public QaService(ProgramRepository programs, SolveService solveService,
                     PlanResultRepository planResults, ObjectProvider<LlmClient> llm,
                     LlmCallLogRepository llmLogs, QaRepository qaRepo, AppProperties props,
                     AgentLoop agentLoop, MemoryService memoryService,
                     com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.programs = programs;
        this.solveService = solveService;
        this.planResults = planResults;
        this.llm = llm;
        this.llmLogs = llmLogs;
        this.qaRepo = qaRepo;
        this.props = props;
        this.agentLoop = agentLoop;
        this.memoryService = memoryService;
        this.mapper = mapper;
    }

    public Answer answer(long userId, long programId, String question) {
        return answer(userId, programId, question, List.of());
    }

    public Answer answer(long userId, long programId, String question, List<Turn> history) {
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }

        qaRepo.append(userId, programId, "user", question, null);
        memoryService.remember(userId, question);
        List<String> memories = memoryService.context(userId);
        String historyBlock = formatHistory(history);

        LlmClient client = llm.getIfAvailable();
        if ("agent".equalsIgnoreCase(props.getQa().getMode()) && client != null) {
            try {
                AgentLoop.LoopResult loop = agentLoop.run(userId, programId, question, memories, historyBlock);
                List<Citation> citations = mergeCitations(program, question, loop.steps());
                qaRepo.append(userId, programId, "assistant", loop.text(), null);
                return new Answer(loop.text(), citations, loop.engine(), loop.steps());
            } catch (Exception e) {
                llmLogs.recordFailure(userId, null, "qa-agent", e.getMessage());
                log.warn("agent loop failed, fallback to classic qa", e);
            }
        }

        SolveService.Dashboard dash = solveService.dashboard(userId, programId);
        List<PlanModels.Plan> plans = planResults.latest(userId, programId);
        List<Citation> citations = retrieve(program, question);
        String context = buildContext(dash, plans, citations, memories, historyBlock);

        if (client == null) {
            String text = templateAnswer(question, dash, plans, citations);
            qaRepo.append(userId, programId, "assistant", text, null);
            return new Answer(text, citations, "template");
        }
        try {
            LlmClient.Result result = client.chat(new LlmClient.Request(
                    "qa", systemPrompt(), context + "\n\n学生问题：" + question, false));
            llmLogs.record(userId, null, "qa", result);
            qaRepo.append(userId, programId, "assistant", result.content(), null);
            return new Answer(result.content(), citations, "llm:" + result.model());
        } catch (Exception e) {
            llmLogs.recordFailure(userId, null, "qa", e.getMessage());
            log.warn("qa llm failed, fallback to template", e);
            String text = templateAnswer(question, dash, plans, citations);
            qaRepo.append(userId, programId, "assistant", text, null);
            return new Answer(text, citations, "template(fallback)");
        }
    }

    private String systemPrompt() {
        return """
                你是课程规划助教。只能使用给定的事实回答问题，不要编造课程、学分或政策。
                如果事实里没有答案，直接说「培养方案里没有写」。回答控制在 200 字以内，用中文。
                """;
    }

    private String buildContext(SolveService.Dashboard dash, List<PlanModels.Plan> plans,
                                List<Citation> citations, List<String> memories, String historyBlock) {
        StringBuilder sb = new StringBuilder();
        if (!memories.isEmpty()) {
            sb.append("用户偏好：");
            sb.append(String.join("；", memories));
            sb.append('\n');
        }
        sb.append("专业：").append(dash.major() == null ? "未知" : dash.major()).append('\n');
        sb.append(String.format("毕业要求总学分 %.1f，已修 %.1f，还差 %.1f；未完成必修课 %d 门%n",
                dash.requiredCredits(), dash.earnedCredits(), dash.remainingCredits(),
                dash.remainingCourses()));
        for (SolveService.ModuleProgress m : dash.modules()) {
            sb.append(String.format("- 模块「%s」要求 %.1f 学分，已修 %.1f，还差 %.1f%n",
                    m.name(), m.required(), m.earned(), m.remaining()));
        }
        if (!plans.isEmpty()) {
            PlanModels.Plan p = plans.get(0);
            sb.append("最近一次求解结果：最快 ").append(p.semesters()).append(" 学期修完，共 ")
                    .append(p.totalCredits()).append(" 学分。\n");
            for (PlanModels.Term t : p.terms()) {
                sb.append("第").append(t.term()).append("学期：");
                sb.append(t.courses().stream().map(PlanModels.Item::name).reduce((a, b) -> a + "、" + b).orElse(""));
                sb.append('\n');
            }
        }
        if (!citations.isEmpty()) {
            sb.append("培养方案原文片段：\n");
            for (Citation c : citations) {
                sb.append("- ").append(c.snippet()).append('\n');
            }
        }
        if (historyBlock != null && !historyBlock.isBlank()) {
            sb.append(historyBlock);
        }
        return sb.toString();
    }

    /** 只保留最近 3 轮，答案截断——上下文给太多反而干扰「指代解析」这件小事。 */
    private static String formatHistory(List<Turn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<Turn> recent = history.subList(Math.max(0, history.size() - HISTORY_LIMIT), history.size());
        StringBuilder sb = new StringBuilder();
        for (Turn turn : recent) {
            if (turn == null || turn.question() == null) {
                continue;
            }
            String answer = turn.answer() == null ? "" : turn.answer().replaceAll("\\s+", " ").trim();
            if (answer.length() > HISTORY_ANSWER_CLIP) {
                answer = answer.substring(0, HISTORY_ANSWER_CLIP) + "…";
            }
            sb.append("学生：").append(turn.question().trim()).append('\n');
            if (!answer.isEmpty()) {
                sb.append("助教：").append(answer).append('\n');
            }
        }
        return sb.toString();
    }

    /** 粗糙但够用的检索：中文按二字组重合度打分，取前 3 段。 */
    private List<Citation> retrieve(ProgramRepository.ProgramRow program, String question) {
        String text = program.sourceText();
        if (text == null || text.isBlank() || question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> q = bigrams(question);
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.length() >= 6 && line.length() <= 120) {
                lines.add(line);
            }
        }
        record Scored(String line, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (String line : lines) {
            Set<String> lb = bigrams(line);
            int hit = 0;
            for (String g : q) {
                if (lb.contains(g)) {
                    hit++;
                }
            }
            if (hit > 0) {
                scored.add(new Scored(line, hit));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        String source = program.sourceFile() == null ? "培养方案" : program.sourceFile();
        return scored.stream().limit(3)
                .map(s -> new Citation(source, s.line()))
                .toList();
    }

    private static Set<String> bigrams(String s) {
        String cleaned = s.replaceAll("[\\s，。、；：？！,.;:?!()（）「」【】]", "");
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            out.add(cleaned.substring(i, i + 2));
        }
        return out;
    }

    private String templateAnswer(String question, SolveService.Dashboard dash,
                                  List<PlanModels.Plan> plans, List<Citation> citations) {
        StringBuilder sb = new StringBuilder();
        sb.append("按照当前培养方案，你还差 ")
                .append(String.format("%.0f", dash.remainingCredits()))
                .append(" 学分，未完成必修课 ")
                .append(dash.remainingCourses())
                .append(" 门。\n");
        dash.modules().stream()
                .filter(m -> m.remaining() > 0)
                .sorted((a, b) -> Double.compare(b.remaining(), a.remaining()))
                .limit(3)
                .forEach(m -> sb.append("「").append(m.name()).append("」还差 ")
                        .append(String.format("%.0f", m.remaining())).append(" 学分；"));
        if (dash.modules().stream().anyMatch(m -> m.remaining() > 0)) {
            sb.append('\n');
        }
        if (!plans.isEmpty()) {
            PlanModels.Plan p = plans.get(0);
            sb.append("最近一次排课方案最快 ").append(p.semesters())
                    .append(" 个学期修完，风险分 ").append(p.riskScore()).append("。\n");
        } else {
            sb.append("还没有生成排课方案，可以在「方案」页点一次求解。\n");
        }
        if (!citations.isEmpty()) {
            sb.append("（依据培养方案原文：").append(citations.get(0).snippet()).append("）");
        }
        return sb.toString();
    }

    /**
     * agent 模式的引用 = 经典检索的结果 ∪ 工具实际检索到的片段。
     * 后者更贴近模型真正看到的东西，所以去重后保留。
     */
    private List<Citation> mergeCitations(ProgramRepository.ProgramRow program, String question,
                                          List<AgentLoop.Step> steps) {
        List<Citation> merged = new ArrayList<>(retrieve(program, question));
        for (AgentLoop.Step step : steps) {
            if (!"search_program".equals(step.tool())) {
                continue;
            }
            try {
                for (var node : mapper.readTree(step.observation()).path("results")) {
                    String snippet = node.path("snippet").asText("");
                    if (snippet.isBlank() || merged.stream().anyMatch(m -> snippet.equals(m.snippet()))) {
                        continue;
                    }
                    merged.add(new Citation(node.path("source").asText("培养方案"), snippet));
                }
            } catch (Exception ignored) {
                // 工具输出截断后可能不是完整 JSON，忽略即可
            }
        }
        return merged.stream().limit(5).toList();
    }

    public List<QaRepository.Message> history(long userId, Long programId) {
        return qaRepo.history(userId, programId, 50);
    }
}
