package com.courseplanner.qa;

import com.courseplanner.common.BizException;
import com.courseplanner.config.AppProperties;
import com.courseplanner.extract.LlmBackedExtractor;
import com.courseplanner.extract.LlmCallLogRepository;
import com.courseplanner.extract.LlmClient;
import com.courseplanner.program.ProgramRepository;
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

    public record Answer(String text, List<Citation> citations, String engine) {
    }

    private final ProgramRepository programs;
    private final SolveService solveService;
    private final PlanResultRepository planResults;
    private final ObjectProvider<LlmClient> llm;
    private final LlmCallLogRepository llmLogs;
    private final QaRepository qaRepo;
    private final AppProperties props;

    public QaService(ProgramRepository programs, SolveService solveService,
                     PlanResultRepository planResults, ObjectProvider<LlmClient> llm,
                     LlmCallLogRepository llmLogs, QaRepository qaRepo, AppProperties props) {
        this.programs = programs;
        this.solveService = solveService;
        this.planResults = planResults;
        this.llm = llm;
        this.llmLogs = llmLogs;
        this.qaRepo = qaRepo;
        this.props = props;
    }

    public Answer answer(long userId, long programId, String question) {
        ProgramRepository.ProgramRow program = programs.findById(programId)
                .orElseThrow(() -> BizException.notFound("培养方案"));
        if (program.userId() != userId) {
            throw BizException.forbidden();
        }

        SolveService.Dashboard dash = solveService.dashboard(userId, programId);
        List<PlanModels.Plan> plans = planResults.latest(userId, programId);
        List<Citation> citations = retrieve(program, question);
        String context = buildContext(dash, plans, citations);

        qaRepo.append(userId, programId, "user", question, null);

        LlmClient client = llm.getIfAvailable();
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
                                List<Citation> citations) {
        StringBuilder sb = new StringBuilder();
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

    public List<QaRepository.Message> history(long userId, Long programId) {
        return qaRepo.history(userId, programId, 50);
    }
}
