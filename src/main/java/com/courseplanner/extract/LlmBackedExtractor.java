package com.courseplanner.extract;

import com.courseplanner.config.AppProperties;
import com.courseplanner.program.ProgramDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 模型抽取。模型只做「翻译」：把非结构化文本翻成 JSON。
 * 它翻错了由后面的校验链兜底，它挂了由 RuleBasedExtractor 兜底。
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "openai-compatible")
public class LlmBackedExtractor implements ProgramExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmBackedExtractor.class);
    private static final int MAX_CHARS = 24_000;

    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final String systemPrompt;

    public LlmBackedExtractor(LlmClient llm, ObjectMapper mapper, AppProperties props) throws Exception {
        this.llm = llm;
        this.mapper = mapper;
        this.props = props;
        this.systemPrompt = new String(new ClassPathResource("prompts/extract-program.txt")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "llm:" + props.getLlm().getModel();
    }

    @Override
    public ExtractResult extract(String text) {
        String cut = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        LlmClient.Result result = llm.chat(new LlmClient.Request(
                "extract-program", systemPrompt, cut, true));
        try {
            String json = stripFence(result.content());
            ProgramDraft draft = mapper.readValue(json, ProgramDraft.class);
            return new ExtractResult(draft, name(), result);
        } catch (Exception e) {
            log.warn("模型输出不是合法 JSON，交给上层降级: {}", e.getMessage());
            throw new LlmExceptions.LlmUnavailable("模型输出无法解析为方案结构", e);
        }
    }

    private static String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                s = s.substring(firstNewline + 1, lastFence);
            }
        }
        return s.trim();
    }
}
