package com.courseplanner.extract;

import com.courseplanner.extract.validate.ValidationChain;
import com.courseplanner.program.ProgramDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 抽取编排：优先模型，失败降级规则，最后统一过校验链。
 * 「模型可用则用，不可用则退」这一点必须写死在代码里，
 * 否则演示时模型一抖，整个流程就挂了。
 */
@Service
public class ProgramExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ProgramExtractionService.class);

    private final RuleBasedExtractor ruleBased;
    private final ObjectProvider<LlmBackedExtractor> llmExtractor;
    private final ValidationChain chain;
    private final LlmCallLogRepository llmLogs;

    public ProgramExtractionService(RuleBasedExtractor ruleBased,
                                    ObjectProvider<LlmBackedExtractor> llmExtractor,
                                    ValidationChain chain,
                                    LlmCallLogRepository llmLogs) {
        this.ruleBased = ruleBased;
        this.llmExtractor = llmExtractor;
        this.chain = chain;
        this.llmLogs = llmLogs;
    }

    public record Extracted(ProgramDraft draft, List<String> warnings, String extractor) {
    }

    public Extracted extract(String text, long userId, Long jobId) {
        List<String> warnings = new ArrayList<>();
        ProgramDraft draft = null;
        String used = ruleBased.name();

        LlmBackedExtractor llm = llmExtractor.getIfAvailable();
        if (llm != null) {
            try {
                ProgramExtractor.ExtractResult r = llm.extract(text);
                draft = r.draft();
                used = r.extractor();
                llmLogs.record(userId, jobId, "extract-program", r.usage());
            } catch (Exception e) {
                llmLogs.recordFailure(userId, jobId, "extract-program", e.getMessage());
                warnings.add("模型抽取失败，已降级为规则抽取：" + e.getMessage());
                log.warn("llm extract failed, fallback to rules", e);
            }
        }

        if (draft == null) {
            draft = ruleBased.extract(text).draft();
            used = ruleBased.name();
        }

        ValidationChain.Outcome outcome = chain.run(draft);
        warnings.addAll(outcome.warnings());
        return new Extracted(outcome.draft(), warnings, used);
    }
}
